package com.tvbox.kotlin.core.player.effect.anime4k

/** 画面参数：色彩/色调/细节调节，对齐 AVBox 的 PictureProfile 语义 */
data class PictureProfileSpec(
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    val gamma: Float = 1f,
    val temperature: Float = 0f,
    val hue: Float = 0f,
    val shadowLift: Float = 0f,
    val sharpness: Float = Anime4kSettings.DEFAULT_SHARPEN,
)

/**
 * 画面预设：完整对齐 AVBox 的预设集合，Custom 表示用户自定义参数。
 * 预设只调整画面参数（按帧读取），切换不重建效果链。
 */
enum class PicturePreset(val label: String) {

    Original("原画"),
    Natural("自然"),
    Vivid("鲜艳"),
    Clear("清晰"),
    Bright("明亮"),
    Cinema("影院"),
    Soft("柔和"),
    Warm("暖色"),
    Cool("冷色"),
    Comfort("护眼"),
    Anime("动画"),
    Sport("运动"),
    Game("游戏"),
    Custom("自定义");

    fun profile(): PictureProfileSpec = when (this) {
        // 原画 = 完全不做画面处理（锐度归零），否则与原画语义不符，
        // 且 isColorNoOp 恒为 false，切换预设时无法跳过效果链重建
        Original -> PictureProfileSpec(sharpness = 0f)
        Natural -> PictureProfileSpec(saturation = 1.02f, contrast = 1.02f, sharpness = 0.30f)
        Vivid -> PictureProfileSpec(
            saturation = 1.26f, contrast = 1.12f, brightness = 0.01f, sharpness = 0.45f
        )

        Clear -> PictureProfileSpec(
            saturation = 1.04f, contrast = 1.12f, shadowLift = 0.02f, sharpness = 0.75f
        )

        Bright -> PictureProfileSpec(
            saturation = 1.04f, contrast = 1.05f, brightness = 0.01f,
            gamma = 1.01f, shadowLift = 0.06f, sharpness = 0.30f
        )

        Cinema -> PictureProfileSpec(
            saturation = 1.04f, contrast = 1.14f, brightness = -0.03f,
            gamma = 0.97f, temperature = 26f, shadowLift = 0.03f, sharpness = 0.25f
        )

        Soft -> PictureProfileSpec(
            saturation = 0.95f, contrast = 0.94f, brightness = 0.005f,
            gamma = 1.03f, temperature = 14f, shadowLift = 0.05f, sharpness = 0.15f
        )

        Warm -> PictureProfileSpec(
            saturation = 1.05f, contrast = 1.04f, temperature = 42f,
            shadowLift = 0.02f, sharpness = 0.25f
        )

        Cool -> PictureProfileSpec(
            saturation = 1.04f, contrast = 1.05f, temperature = -42f,
            shadowLift = 0.02f, sharpness = 0.25f
        )

        Comfort -> PictureProfileSpec(
            saturation = 0.92f, contrast = 0.93f, brightness = -0.01f,
            gamma = 1.04f, temperature = 58f, shadowLift = 0.06f, sharpness = 0.15f
        )

        Anime -> PictureProfileSpec(
            saturation = 1.24f, contrast = 1.10f, brightness = 0.02f,
            shadowLift = 0.02f, sharpness = 0.55f
        )

        Sport -> PictureProfileSpec(
            saturation = 1.12f, contrast = 1.14f, brightness = 0.02f,
            shadowLift = 0.04f, sharpness = 0.45f
        )

        Game -> PictureProfileSpec(
            saturation = 1.08f, contrast = 1.14f, brightness = 0.02f,
            shadowLift = 0.04f, sharpness = 0.45f
        )

        Custom -> PictureProfileSpec(
            Anime4kSettings.saturation(),
            Anime4kSettings.contrast(),
            Anime4kSettings.brightness(),
            Anime4kSettings.gamma(),
            Anime4kSettings.temperature(),
            Anime4kSettings.hue(),
            Anime4kSettings.shadowLift(),
            Anime4kSettings.sharpen(),
        )
    }

    companion object {
        fun byNameOrCustom(name: String?): PicturePreset =
            entries.firstOrNull { it.name == name } ?: Custom
    }
}
