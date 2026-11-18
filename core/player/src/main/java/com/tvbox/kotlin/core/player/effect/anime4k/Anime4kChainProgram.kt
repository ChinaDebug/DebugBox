package com.tvbox.kotlin.core.player.effect.anime4k

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import com.tvbox.kotlin.core.common.LOG

internal class Anime4kChainProgram(
    private val context: Context,
    private val tier: Anime4kTier?,
    hdr: Boolean,
) : BaseGlShaderProgram(hdr, if (hdr) 1 else 3) {

    private val passes = ArrayList<CompiledPass>()
    private val targets = ArrayList<Target>()
    private var copyProgram: GlProgram? = null
    private var outputWidth = 0
    private var outputHeight = 0
    private var inputWidth = 0
    private var inputHeight = 0
    private var failed = hdr || tier == null

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        releaseGl()
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        outputWidth = inputWidth
        outputHeight = inputHeight
        buildChainIfNeeded()
        return Size(outputWidth, outputHeight)
    }

    /** 构建效果链：在 configure 阶段完成，保证开关增强时立即生效、无需重建视频管线 */
    private fun buildChainIfNeeded() {
        if (failed || passes.isNotEmpty()) return
        if (inputWidth <= 0 || inputHeight <= 0) return
        try {
            build(requireNotNull(tier), inputWidth, inputHeight)
            Anime4kStatus.onBuildSucceeded(passes.size)
        } catch (e: Exception) {
            failed = true
            passes.clear()
            releaseTargets()
            Anime4kStatus.onBuildFailed()
            LOG.e(
                "Anime4kChain",
                "效果链构建失败，降级为直通渲染: ${e.javaClass.simpleName}: ${e.message}",
                e,
            )
        }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        // 播放中开启增强：不重建视频管线，直接在 GL 线程补建效果链
        if (!failed && passes.isEmpty()) buildChainIfNeeded()
        try {
            if (failed || passes.isEmpty() || !Anime4kSettings.enabled()) {
                drawCopy(inputTexId)
                return
            }
            val previousFramebuffer = IntArray(1)
            val previousViewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0)
            passes.forEachIndexed { index, compiled ->
                try {
                    if (compiled.writesScreen) {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
                        GLES20.glViewport(
                            previousViewport[0],
                            previousViewport[1],
                            previousViewport[2],
                            previousViewport[3],
                        )
                    } else {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, compiled.targetFramebuffer)
                        GLES20.glViewport(0, 0, compiled.width, compiled.height)
                    }
                    val program = compiled.program
                    program.use()
                    compiled.bindings.forEachIndexed { unit, binding ->
                        val textureId = if (binding.isInput) inputTexId else binding.textureId
                        if (binding.sampler) {
                            try {
                                program.setSamplerTexIdUniform("uTex${binding.name}", textureId, unit)
                            } catch (e: RuntimeException) {
                                binding.sampler = false
                            }
                        }
                        program.setFloatsUniformIfPresent(
                            "uPt${binding.name}",
                            floatArrayOf(1f / binding.width, 1f / binding.height),
                        )
                        program.setFloatsUniformIfPresent(
                            "uSize${binding.name}",
                            floatArrayOf(binding.width.toFloat(), binding.height.toFloat()),
                        )
                    }
                    // 输出 pass 只做几何缩放，细节锐化由独立的 PictureColorEffect 负责
                    program.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                } catch (e: RuntimeException) {
                    LOG.e("Anime4kChain", "第 ${index + 1}/${passes.size} 个 pass 绘制失败", e)
                    throw e
                }
            }
            Anime4kStatus.onFrameDrawn()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        releaseGl()
    }

    private fun build(tier: Anime4kTier, inputWidth: Int, inputHeight: Int) {
        fun load(asset: String): List<Anime4kPass> = Anime4kShader.parse(
            context.assets.open(ANIME4K_ASSET_DIR + asset).bufferedReader().use { it.readText() },
        )

        val canvas = EffectCanvas.get()
        val clamp = load(ANIME4K_CLAMP_HIGHLIGHTS)
        val restore = tier.restore?.let { load(it) }.orEmpty()
        val upscale = tier.upscale?.let { load(it) }.orEmpty()
        val deblur = load(ANIME4K_DEBLUR_DOG)
        val deblurOn = Anime4kSettings.deblur()
        val composed = Anime4kShader.compose(restore, upscale, deblur, clamp, inputWidth, canvas, deblurOn)
        val planned = Anime4kShader.planPasses(composed, inputWidth, inputHeight, canvas)
            .toMutableList()
        if (planned.isEmpty()) throw IllegalStateException("no pass parsed: tier=${tier.name}")
        if (planned.last().pass.save != Anime4kShader.MAIN) {
            throw IllegalStateException("last pass must SAVE MAIN: ${planned.last().pass.name}")
        }
        val byTarget = HashMap<Int, Target>()
        Anime4kShader.assignTargets(planned).forEach { item ->
            val plannedPass = item.planned
            val program = GlProgram(VERTEX_SHADER, plannedPass.pass.fragmentShader)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            val bindings = item.bindings.map { binding ->
                val target = byTarget[binding.target]
                BoundBinding(binding.name, target?.texture ?: 0, target == null, binding.width, binding.height)
            }
            val targetFramebuffer: Int
            if (item.outputTarget == Anime4kShader.SCREEN_TARGET) {
                targetFramebuffer = 0
            } else {
                val target = byTarget[item.outputTarget]
                    ?: newTarget(plannedPass.width, plannedPass.height).also { byTarget[item.outputTarget] = it }
                targetFramebuffer = target.framebuffer
            }
            passes.add(
                CompiledPass(
                    program = program,
                    bindings = bindings,
                    targetFramebuffer = targetFramebuffer,
                    width = plannedPass.width,
                    height = plannedPass.height,
                    writesScreen = plannedPass.writesScreen,
                ),
            )
        }
        val last = planned.last()
        outputWidth = last.width
        outputHeight = last.height
    }

    private fun newTarget(width: Int, height: Int): Target {
        val texture = GlUtil.createTexture(width, height, false)
        val target = Target(texture, createFramebuffer(texture))
        targets.add(target)
        return target
    }

    private fun drawCopy(inputTexId: Int) {
        val program = copyProgram ?: GlProgram(VERTEX_SHADER, COPY_SHADER).also {
            it.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            copyProgram = it
        }
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun createFramebuffer(textureId: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            textureId,
            0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return ids[0]
    }

    private fun deleteFramebuffer(id: Int) {
        GLES20.glDeleteFramebuffers(1, intArrayOf(id), 0)
    }

    private fun releaseTargets() {
        targets.forEach { target ->
            try {
                GlUtil.deleteTexture(target.texture)
            } catch (e: GlUtil.GlException) {
                LOG.e("Anime4kChain", "delete target texture failed", e)
            }
            deleteFramebuffer(target.framebuffer)
        }
        targets.clear()
    }

    private fun releaseGl() {
        try {
            passes.forEach { it.program.delete() }
            copyProgram?.delete()
        } catch (e: GlUtil.GlException) {
            LOG.e("Anime4kChain", "release programs failed", e)
        }
        passes.clear()
        releaseTargets()
        copyProgram = null
    }

    private class Target(val texture: Int, val framebuffer: Int)

    private class BoundBinding(
        val name: String,
        val textureId: Int,
        val isInput: Boolean,
        val width: Int,
        val height: Int,
    ) {
        var sampler = true
    }

    private class CompiledPass(
        val program: GlProgram,
        val bindings: List<BoundBinding>,
        val targetFramebuffer: Int,
        val width: Int,
        val height: Int,
        val writesScreen: Boolean,
    )

    private companion object {

        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexCoord = aFramePosition.xy * 0.5 + 0.5;
            }
            """

        const val COPY_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexCoord;
            void main() {
              gl_FragColor = texture2D(uTexSampler, vTexCoord);
            }
            """
    }
}
