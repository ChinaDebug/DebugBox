package com.tvbox.kotlin.core.player.effect.anime4k

internal data class Anime4kPass(
    val name: String,
    val binds: List<String>,
    val save: String,
    val widthExpr: List<String>,
    val heightExpr: List<String>,
    val components: Int,
    val whenExpr: List<String>,
    val fragmentShader: String,
)

internal data class Anime4kBinding(val name: String, val width: Int, val height: Int)

internal data class Anime4kPlannedPass(
    val pass: Anime4kPass,
    val width: Int,
    val height: Int,
    val writesScreen: Boolean,
    val bindings: List<Anime4kBinding>,
)

internal data class Anime4kBindingSource(val name: String, val target: Int, val width: Int, val height: Int)

internal data class Anime4kPassIo(
    val planned: Anime4kPlannedPass,
    val bindings: List<Anime4kBindingSource>,
    val outputTarget: Int,
)

internal object Anime4kShader {

    const val MAIN = "MAIN"

    const val HOOKED = "HOOKED"

    const val INPUT_BINDING = -1

    const val SCREEN_TARGET = -1

    fun assignTargets(planned: List<Anime4kPlannedPass>): List<Anime4kPassIo> {
        val latest = HashMap<String, Int>()
        val sizes = HashMap<String, IntArray>()
        val result = ArrayList<Anime4kPassIo>()
        var nextTarget = 0
        planned.forEach { item ->
            val bindings = item.bindings.map { binding ->
                Anime4kBindingSource(
                    name = binding.name,
                    target = latest[binding.name] ?: INPUT_BINDING,
                    width = binding.width,
                    height = binding.height,
                )
            }
            var outputTarget = SCREEN_TARGET
            if (!item.writesScreen) {
                val save = item.pass.save
                val existing = latest[save]
                val readsSave = item.bindings.any { it.name == save || it.name == HOOKED }
                val reusable = existing != null && !readsSave &&
                    sizes[save]?.let { it[0] == item.width && it[1] == item.height } == true
                outputTarget = if (reusable && existing != null) {
                    existing
                } else {
                    nextTarget++
                    nextTarget - 1
                }
                latest[save] = outputTarget
                sizes[save] = intArrayOf(item.width, item.height)
                if (save == MAIN) latest[HOOKED] = outputTarget
            }
            result.add(Anime4kPassIo(item, bindings, outputTarget))
        }
        return result
    }

    private const val DIRECTIVE = "//!"

    private const val PRECISION = "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
        "precision highp float;\n" +
        "#else\n" +
        "precision mediump float;\n" +
        "#endif\n"

    private const val ENTRY = "void main() {\n  gl_FragColor = hook();\n}\n"

    private const val PRESENT_BOX_BODY = "vec4 hook() {\n" +
        "    return (MAIN_tex(MAIN_pos + vec2(-0.5, -0.5) * MAIN_pt)\n" +
        "        + MAIN_tex(MAIN_pos + vec2(0.5, -0.5) * MAIN_pt)\n" +
        "        + MAIN_tex(MAIN_pos + vec2(-0.5, 0.5) * MAIN_pt)\n" +
        "        + MAIN_tex(MAIN_pos + vec2(0.5, 0.5) * MAIN_pt)) * 0.25;\n" +
        "}\n"

    /** 输出 pass：只做尺寸缩放采样，细节锐化由 PictureColorEffect 独立负责 */
    private const val PRESENT_SAMPLE_BODY = "vec4 hook() {\n" +
        "    return MAIN_tex(MAIN_pos);\n" +
        "}\n"

    private val IVEC_DECL = Regex("""(?m)^(\s*)ivec2\s+(\w+)\s*=\s*ivec2\((\w+)\s*\*\s*vec2\(2\.0\)\)\s*;""")

    private val DYNAMIC_INDEX =
        Regex("""(?m)^(\s*)(?:\w+\s+)?(\w+)\s*=\s*([^;]*?)\[(\w+)\.y\s*\*\s*2\s*\+\s*\4\.x\]\s*;""")

    fun parse(text: String): List<Anime4kPass> = splitBlocks(text).map(::buildPass)

    private const val UPSCALE_MIN_RATIO = 1.35f

    private const val DOUBLE_UPSCALE_MIN_RATIO = 2.5f

    fun compose(
        restore: List<Anime4kPass>,
        upscale: List<Anime4kPass>,
        deblur: List<Anime4kPass>,
        clamp: List<Anime4kPass>,
        inputWidth: Int,
        canvas: IntArray?,
        deblurEnabled: Boolean,
    ): List<Anime4kPass> {
        val ratio = canvas?.let { it[0].toFloat() / inputWidth } ?: 1f
        var count = when {
            upscale.isEmpty() -> 0
            canvas == null -> 1
            ratio < UPSCALE_MIN_RATIO -> 0
            ratio >= DOUBLE_UPSCALE_MIN_RATIO -> 2
            else -> 1
        }
        if (count == 0 && restore.isEmpty() && !deblurEnabled && upscale.isNotEmpty()) count = 1
        val passes = ArrayList<Anime4kPass>()
        passes.addAll(clamp.dropLast(1))
        passes.addAll(restore)
        if (deblurEnabled) passes.addAll(deblur)
        passes.addAll(clamp.takeLast(1))
        repeat(count) { passes.addAll(upscale) }
        return passes
    }

    fun planPasses(
        passes: List<Anime4kPass>,
        inputWidth: Int,
        inputHeight: Int,
        canvas: IntArray? = null,
    ): List<Anime4kPlannedPass> {
        val sizes = HashMap<String, IntArray>()
        sizes[MAIN] = intArrayOf(inputWidth, inputHeight)
        sizes[HOOKED] = intArrayOf(inputWidth, inputHeight)
        val planned = ArrayList<Anime4kPlannedPass>()
        passes.forEach { pass ->
            val current = sizes[HOOKED] ?: intArrayOf(inputWidth, inputHeight)
            val width = resolve(pass.widthExpr, sizes, current[0])
            val height = resolve(pass.heightExpr, sizes, current[1])
            sizes[pass.save] = intArrayOf(width, height)
            if (pass.save == MAIN) sizes[HOOKED] = intArrayOf(width, height)
            val bindings = pass.binds.map { name ->
                val bound = sizes[name] ?: intArrayOf(width, height)
                Anime4kBinding(name, bound[0], bound[1])
            }
            planned.add(Anime4kPlannedPass(pass, width, height, false, bindings))
        }
        val result = ArrayList(planned)
        val last = result.lastOrNull()
        val target = presentedSize(inputWidth, inputHeight, last, canvas)
        if (last != null && (last.width != target[0] || last.height != target[1])) {
            // 仅当输出尺寸与画布不一致时补输出 pass 做缩放（放大或降采样）；
            // 同尺寸的细节锐化由独立的 PictureColorEffect 负责，避免多一次无意义的拷贝
            result.add(
                Anime4kPlannedPass(
                    pass = presentPass(
                        target[0],
                        target[1],
                        box = last.width > target[0] * BOX_DOWNSCALE_RATIO,
                    ),
                    width = target[0],
                    height = target[1],
                    writesScreen = true,
                    bindings = listOf(Anime4kBinding(MAIN, last.width, last.height)),
                ),
            )
        }
        return result.mapIndexed { index, item -> item.copy(writesScreen = index == result.lastIndex) }
    }

    private fun presentedSize(
        inputWidth: Int,
        inputHeight: Int,
        last: Anime4kPlannedPass?,
        canvas: IntArray?,
    ): IntArray {
        val source = intArrayOf(inputWidth, inputHeight)
        if (last == null || canvas == null) return source
        val width = canvas[0]
        val height = canvas[1]
        if (width < inputWidth || height < inputHeight) return source
        if (!sameAspect(width, height, inputWidth, inputHeight)) return source
        return intArrayOf(width, height)
    }

    private fun sameAspect(width: Int, height: Int, otherWidth: Int, otherHeight: Int): Boolean {
        val left = width.toLong() * otherHeight
        val right = otherWidth.toLong() * height
        return kotlin.math.abs(left - right) * 100 <= right
    }

    fun resolveSize(expr: List<String>, widthOf: (String) -> Int, heightOf: (String) -> Int): Int {
        val stack = ArrayList<Int>()
        expr.forEach { token ->
            when {
                token == "*" -> {
                    if (stack.size < 2) return@forEach
                    val b = stack.removeAt(stack.lastIndex)
                    val a = stack.removeAt(stack.lastIndex)
                    stack.add(a * b)
                }

                token.endsWith(".w") -> stack.add(widthOf(token.dropLast(2)))
                token.endsWith(".h") -> stack.add(heightOf(token.dropLast(2)))
                else -> token.toIntOrNull()?.let { stack.add(it) }
            }
        }
        return stack.lastOrNull() ?: 0
    }

    fun adaptToEs2(body: String): String {
        var adapted = IVEC_DECL.replace(body) { match ->
            val indent = match.groupValues[1]
            val index = match.groupValues[2]
            val fraction = match.groupValues[3]
            "${indent}float ${index}x = step(0.5, ${fraction}.x);\n" +
                "${indent}float ${index}y = step(0.5, ${fraction}.y);"
        }
        adapted = DYNAMIC_INDEX.replace(adapted) { match ->
            val indent = match.groupValues[1]
            val target = match.groupValues[2]
            val sample = match.groupValues[3]
            val index = match.groupValues[4]
            "${indent}vec4 ${index}v = ${sample};\n" +
                "${indent}float ${target} = mix(mix(${index}v.x, ${index}v.y, ${index}x)," +
                " mix(${index}v.z, ${index}v.w, ${index}x), ${index}y);"
        }
        return adapted
    }

    private const val BOX_DOWNSCALE_RATIO = 1.25f

    private fun presentPass(width: Int, height: Int, box: Boolean): Anime4kPass = Anime4kPass(
        name = "present",
        binds = listOf(MAIN),
        save = MAIN,
        widthExpr = listOf(width.toString()),
        heightExpr = listOf(height.toString()),
        components = 4,
        whenExpr = emptyList(),
        fragmentShader = fragmentShader(
            if (box) PRESENT_BOX_BODY else PRESENT_SAMPLE_BODY,
            listOf(MAIN),
        ),
    )

    private fun resolve(expr: List<String>, sizes: Map<String, IntArray>, fallback: Int): Int {
        val value = resolveSize(
            expr,
            { sizes[it]?.get(0) ?: fallback },
            { sizes[it]?.get(1) ?: fallback },
        )
        return if (value > 0) value else fallback
    }

    private class Block(val header: List<String>, val body: String)

    private fun splitBlocks(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        var header: MutableList<String>? = null
        var body: StringBuilder? = null
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.startsWith(DIRECTIVE + "DESC") -> {
                    val previous = header
                    if (previous != null) blocks.add(Block(previous, body?.toString().orEmpty()))
                    header = ArrayList<String>().apply { add(line) }
                    body = StringBuilder()
                }

                line.startsWith(DIRECTIVE) -> header?.add(line)
                else -> body?.append(line)?.append('\n')
            }
        }
        val last = header
        if (last != null) blocks.add(Block(last, body?.toString().orEmpty()))
        return blocks
    }

    private fun buildPass(block: Block): Anime4kPass {
        val binds = ArrayList<String>()
        var name = ""
        var save = MAIN
        var widthExpr: List<String> = emptyList()
        var heightExpr: List<String> = emptyList()
        var components = 4
        var whenExpr: List<String> = emptyList()
        block.header.forEach { line ->
            val tokens = line.removePrefix(DIRECTIVE).trim().split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return@forEach
            val value = tokens.drop(1)
            when (tokens[0]) {
                "DESC" -> name = value.joinToString(" ")
                "BIND" -> value.firstOrNull()?.let { binds.add(it) }
                "SAVE" -> value.firstOrNull()?.let { save = it }
                "WIDTH" -> widthExpr = value
                "HEIGHT" -> heightExpr = value
                "COMPONENTS" -> components = value.firstOrNull()?.toIntOrNull() ?: components
                "WHEN" -> whenExpr = value
            }
        }
        val usedBinds = binds.filter { name ->
            val alias = when (name) {
                HOOKED -> MAIN
                MAIN -> HOOKED
                else -> null
            }
            block.body.contains("${name}_") || (alias != null && block.body.contains("${alias}_"))
        }
        return Anime4kPass(
            name = name,
            binds = usedBinds,
            save = save,
            widthExpr = widthExpr,
            heightExpr = heightExpr,
            components = components,
            whenExpr = whenExpr,
            fragmentShader = fragmentShader(block.body, usedBinds),
        )
    }

    private fun fragmentShader(body: String, binds: List<String>): String = buildString {
        append(PRECISION)
        append("varying vec2 vTexCoord;\n")
        val emitted = LinkedHashSet<String>()
        binds.forEach { tex ->
            emitted.add(tex)
            append("uniform sampler2D uTex$tex;\n")
            append("uniform vec2 uPt$tex;\n")
            append("uniform vec2 uSize$tex;\n")
            appendTexMacros(tex, tex)
        }
        binds.forEach { tex ->
            val alias = when (tex) {
                HOOKED -> MAIN
                MAIN -> HOOKED
                else -> null
            }
            if (alias != null && emitted.add(alias)) appendTexMacros(alias, tex)
        }
        val adapted = adaptToEs2(body)
        append(adapted)
        if (!adapted.endsWith("\n")) append('\n')
        append(ENTRY)
    }

    private fun StringBuilder.appendTexMacros(name: String, uniform: String) {
        append("#define ${name}_tex(p) texture2D(uTex$uniform, (p))\n")
        append("#define ${name}_texOff(off) texture2D(uTex$uniform, vTexCoord + (off) * uPt$uniform)\n")
        append("#define ${name}_pos vTexCoord\n")
        append("#define ${name}_pt uPt$uniform\n")
        append("#define ${name}_size uSize$uniform\n")
    }
}
