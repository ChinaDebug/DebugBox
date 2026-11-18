package com.tvbox.kotlin.core.player.effect.anime4k

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * 画面调节效果（对标 AVBox PictureEffects）：饱和度/对比度/亮度/伽马/色相/色温/锐度/阴影提升。
 * 参数按帧从 [Anime4kSettings] 读取，切换预设或拖动滑杆即时生效，无需重建效果链。
 */
internal class PictureColorEffect : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        PictureColorProgram(useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

internal class PictureColorProgram(hdr: Boolean) : BaseGlShaderProgram(hdr, 1) {

    private var width = 1
    private var height = 1

    private val program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
        setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth.coerceAtLeast(1)
        height = inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        // 参数全默认时让 shader 直接输出原像素，省掉一整套色彩运算
        program.setFloatsUniformIfPresent(
            "uNoOp",
            floatArrayOf(if (Anime4kSettings.isColorNoOp()) 1f else 0f)
        )
        program.setFloatsUniformIfPresent("uTexel", floatArrayOf(1f / width, 1f / height))
        program.setFloatsUniformIfPresent("uSaturation", floatArrayOf(Anime4kSettings.saturation()))
        program.setFloatsUniformIfPresent("uContrast", floatArrayOf(Anime4kSettings.contrast()))
        program.setFloatsUniformIfPresent("uBrightness", floatArrayOf(Anime4kSettings.brightness()))
        program.setFloatsUniformIfPresent("uGamma", floatArrayOf(Anime4kSettings.gamma()))
        program.setFloatsUniformIfPresent("uTemperature", floatArrayOf(Anime4kSettings.temperature()))
        program.setFloatsUniformIfPresent("uHue", floatArrayOf(Anime4kSettings.hue()))
        program.setFloatsUniformIfPresent("uShadowLift", floatArrayOf(Anime4kSettings.shadowLift()))
        program.setFloatsUniformIfPresent("uSharpness", floatArrayOf(Anime4kSettings.sharpen()))
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (_: GlUtil.GlException) {
            // ignore: 释放阶段无补救手段
        }
    }

    private companion object {

        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexCoord = aFramePosition.xy * 0.5 + 0.5;
            }
            """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexel;
            uniform float uNoOp;
            uniform float uSaturation;
            uniform float uContrast;
            uniform float uBrightness;
            uniform float uGamma;
            uniform float uTemperature;
            uniform float uHue;
            uniform float uShadowLift;
            uniform float uSharpness;
            varying vec2 vTexCoord;

            vec3 hueRotate(vec3 color, float degrees) {
              float angle = radians(degrees);
              float s = sin(angle);
              float c = cos(angle);
              vec3 rgb = color;
              vec3 shifted;
              shifted.r = rgb.r * (0.299 + 0.701 * c + 0.168 * s)
                        + rgb.g * (0.587 - 0.587 * c + 0.330 * s)
                        + rgb.b * (0.114 - 0.114 * c - 0.497 * s);
              shifted.g = rgb.r * (0.299 - 0.299 * c - 0.328 * s)
                        + rgb.g * (0.587 + 0.413 * c + 0.035 * s)
                        + rgb.b * (0.114 - 0.114 * c + 0.292 * s);
              shifted.b = rgb.r * (0.299 - 0.300 * c + 1.250 * s)
                        + rgb.g * (0.587 - 0.588 * c - 1.050 * s)
                        + rgb.b * (0.114 + 0.886 * c - 0.203 * s);
              return shifted;
            }

            void main() {
              vec4 src = texture2D(uTexSampler, vTexCoord);
              // 画面参数全为默认值：直接直通，只保留效果链本身避免管线重建
              if (uNoOp > 0.5) {
                gl_FragColor = src;
                return;
              }
              vec3 rgb = src.rgb;

              // 细节锐化：标准非锐化掩模（对标 AVBox DetailAdjustEffect）。
              // 用 4*center - 四邻域和（未除 4），强度是均值版的 4 倍；
              // 阈值遮罩只放大真实边缘，避免平坦区噪点被增强。
              if (uSharpness > 0.001) {
                vec3 left = texture2D(uTexSampler, vTexCoord - vec2(uTexel.x, 0.0)).rgb;
                vec3 right = texture2D(uTexSampler, vTexCoord + vec2(uTexel.x, 0.0)).rgb;
                vec3 up = texture2D(uTexSampler, vTexCoord - vec2(0.0, uTexel.y)).rgb;
                vec3 down = texture2D(uTexSampler, vTexCoord + vec2(0.0, uTexel.y)).rgb;
                vec3 edge = rgb * 4.0 - left - right - up - down;
                float edgeStrength = max(max(abs(edge.r), abs(edge.g)), abs(edge.b));
                float mask = smoothstep(0.02, 0.06, edgeStrength);
                rgb = clamp(rgb + edge * uSharpness * mask, 0.0, 1.0);
              }

              rgb = rgb + uBrightness;
              rgb = (rgb - 0.5) * uContrast + 0.5;
              rgb = pow(max(rgb, vec3(0.0)), vec3(1.0 / max(uGamma, 0.01)));
              rgb.r = rgb.r * (1.0 + uTemperature * 0.0015);
              rgb.b = rgb.b * (1.0 - uTemperature * 0.0012);
              rgb = clamp(rgb, 0.0, 1.0);

              if (abs(uHue) > 0.01) {
                rgb = clamp(hueRotate(rgb, uHue), 0.0, 1.0);
              }

              float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
              rgb = mix(vec3(luma), rgb, uSaturation);

              // 阴影提升：只作用于暗部，越暗提升越明显
              if (uShadowLift > 0.001) {
                float weight = 1.0 - smoothstep(0.0, 0.5, luma);
                rgb = rgb + (1.0 - rgb) * uShadowLift * weight;
              }

              gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), src.a);
            }
            """
    }
}
