package com.tvbox.kotlin.core.player.effect.anime4k

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import com.tvbox.kotlin.core.common.util.AppPreferences

/** Anime4K 着色器资源目录（assets） */
internal const val ANIME4K_ASSET_DIR = "Anime4K/"

internal const val ANIME4K_CLAMP_HIGHLIGHTS = "Anime4K_Clamp_Highlights.glsl"

internal const val ANIME4K_DEBLUR_DOG = "Anime4K_Deblur_DoG.glsl"

private const val RESTORE_CNN_S = "Anime4K_Restore_CNN_S.glsl"
private const val RESTORE_CNN_M = "Anime4K_Restore_CNN_M.glsl"
private const val RESTORE_CNN_L = "Anime4K_Restore_CNN_L.glsl"
private const val UPSCALE_CNN_X2_S = "Anime4K_Upscale_CNN_x2_S.glsl"
private const val UPSCALE_CNN_X2_M = "Anime4K_Upscale_CNN_x2_M.glsl"
private const val UPSCALE_CNN_X2_L = "Anime4K_Upscale_CNN_x2_L.glsl"

/** Anime4K 偏好键（设置页与播放器共用） */
object Anime4kKeys {
    const val ENABLED = "anime4k_enabled"
    const val TIER = "anime4k_tier"
    const val DEBLUR = "anime4k_deblur"
    const val SHARPEN = "anime4k_sharpen"
    const val SATURATION = "picture_saturation"
    const val CONTRAST = "picture_contrast"
    const val BRIGHTNESS = "picture_brightness"
    const val GAMMA = "picture_gamma"
    const val TEMPERATURE = "picture_temperature"
    const val HUE = "picture_hue"
    const val SHADOW_LIFT = "picture_shadow_lift"
    const val PRESET = "picture_preset"
}

/** Anime4K 档位：restore 为画质修复链，upscale 为 2x 放大链 */
enum class Anime4kTier(
    val label: String,
    val restore: String?,
    val upscale: String?
) {
    Light("轻量", RESTORE_CNN_S, null),
    Standard("标准", RESTORE_CNN_S, UPSCALE_CNN_X2_S),
    High("高清", RESTORE_CNN_M, UPSCALE_CNN_X2_M),
    Ultra("极致", RESTORE_CNN_L, UPSCALE_CNN_X2_L),
    UpscaleOnly("仅放大", null, UPSCALE_CNN_X2_S);

    val assets: List<String> get() = listOfNotNull(restore, upscale)

    companion object {

        val default: Anime4kTier = Standard

        fun current(context: Context): Anime4kTier =
            byName(AppPreferences.getShared(context).getString(Anime4kKeys.TIER, default.name))

        fun byName(name: String): Anime4kTier = entries.firstOrNull { it.name == name } ?: default

        fun set(context: Context, tier: Anime4kTier) {
            AppPreferences.getShared(context).putSync(Anime4kKeys.TIER, tier.name)
        }
    }
}

/**
 * Anime4K 运行参数缓存。
 *
 * 偏好只在主线程 [load] 时读取一次；GL 线程构建/绘制时只读缓存，避免跨线程访问存储。
 */
object Anime4kSettings {

    /** 默认锐度：与 AVBox 一致默认开启细节锐化，取画面调节滑杆上限（0~0.8） */
    const val DEFAULT_SHARPEN = 0.8f
    const val DEFAULT_SATURATION = 1f
    const val DEFAULT_CONTRAST = 1f
    const val DEFAULT_BRIGHTNESS = 0f
    const val DEFAULT_GAMMA = 1f
    const val DEFAULT_TEMPERATURE = 0f
    const val DEFAULT_HUE = 0f
    const val DEFAULT_SHADOW_LIFT = 0f

    @Volatile
    private var enabledCached = false

    @Volatile
    private var deblurCached = false

    @Volatile
    private var sharpenCached = DEFAULT_SHARPEN

    @Volatile
    private var saturationCached = DEFAULT_SATURATION

    @Volatile
    private var contrastCached = DEFAULT_CONTRAST

    @Volatile
    private var brightnessCached = DEFAULT_BRIGHTNESS

    @Volatile
    private var gammaCached = DEFAULT_GAMMA

    @Volatile
    private var temperatureCached = DEFAULT_TEMPERATURE

    @Volatile
    private var hueCached = DEFAULT_HUE

    @Volatile
    private var shadowLiftCached = DEFAULT_SHADOW_LIFT

    /** 主线程载入偏好（每次播放前调用） */
    fun load(context: Context) {
        val prefs = AppPreferences.getShared(context)
        enabledCached = prefs.getBoolean(Anime4kKeys.ENABLED, false)
        deblurCached = prefs.getBoolean(Anime4kKeys.DEBLUR, false)
        sharpenCached = prefs.getFloat(Anime4kKeys.SHARPEN, DEFAULT_SHARPEN).coerceIn(0f, 0.8f)
        saturationCached = prefs.getFloat(Anime4kKeys.SATURATION, DEFAULT_SATURATION)
        contrastCached = prefs.getFloat(Anime4kKeys.CONTRAST, DEFAULT_CONTRAST)
        brightnessCached = prefs.getFloat(Anime4kKeys.BRIGHTNESS, DEFAULT_BRIGHTNESS)
        gammaCached = prefs.getFloat(Anime4kKeys.GAMMA, DEFAULT_GAMMA)
        temperatureCached = prefs.getFloat(Anime4kKeys.TEMPERATURE, DEFAULT_TEMPERATURE)
        hueCached = prefs.getFloat(Anime4kKeys.HUE, DEFAULT_HUE)
        shadowLiftCached = prefs.getFloat(Anime4kKeys.SHADOW_LIFT, DEFAULT_SHADOW_LIFT)
    }

    fun enabled(): Boolean = enabledCached

    fun deblur(): Boolean = deblurCached

    fun sharpen(): Float = sharpenCached

    fun saturation(): Float = saturationCached

    fun contrast(): Float = contrastCached

    fun brightness(): Float = brightnessCached

    fun gamma(): Float = gammaCached

    fun temperature(): Float = temperatureCached

    fun hue(): Float = hueCached

    fun shadowLift(): Float = shadowLiftCached

    /**
     * 颜色效果是否能整体跳过：颜色参数全默认且锐度为 0 时才可跳过。
     * 锐度默认非 0，因此默认状态下仍会装配该效果以保证细节锐化生效。
     */
    fun isColorNoOp(): Boolean =
        saturationCached == DEFAULT_SATURATION && contrastCached == DEFAULT_CONTRAST &&
            brightnessCached == DEFAULT_BRIGHTNESS && gammaCached == DEFAULT_GAMMA &&
            temperatureCached == DEFAULT_TEMPERATURE && hueCached == DEFAULT_HUE &&
            shadowLiftCached == DEFAULT_SHADOW_LIFT && sharpenCached <= 0.001f

    /** 应用画面预设：色彩参数与锐度均按帧读取，无需重建效果链 */
    fun applyPreset(context: Context, preset: PicturePreset) {
        val prefs = AppPreferences.getShared(context)
        if (preset != PicturePreset.Custom) {
            val profile = preset.profile()
            saturationCached = profile.saturation
            contrastCached = profile.contrast
            brightnessCached = profile.brightness
            gammaCached = profile.gamma
            temperatureCached = profile.temperature
            hueCached = profile.hue
            shadowLiftCached = profile.shadowLift
            sharpenCached = profile.sharpness.coerceIn(0f, 0.8f)
            prefs.putSync(Anime4kKeys.SATURATION, saturationCached)
            prefs.putSync(Anime4kKeys.CONTRAST, contrastCached)
            prefs.putSync(Anime4kKeys.BRIGHTNESS, brightnessCached)
            prefs.putSync(Anime4kKeys.GAMMA, gammaCached)
            prefs.putSync(Anime4kKeys.TEMPERATURE, temperatureCached)
            prefs.putSync(Anime4kKeys.HUE, hueCached)
            prefs.putSync(Anime4kKeys.SHADOW_LIFT, shadowLiftCached)
            prefs.putSync(Anime4kKeys.SHARPEN, sharpenCached)
        }
        prefs.putSync(Anime4kKeys.PRESET, preset.name)
    }

    /** 自定义画面参数：缓存立即生效，持久化供下次播放使用 */
    fun setColor(
        context: Context,
        saturation: Float,
        contrast: Float,
        brightness: Float,
        gamma: Float,
        temperature: Float,
        hue: Float,
        shadowLift: Float,
        sharpness: Float,
    ) {
        saturationCached = saturation
        contrastCached = contrast
        brightnessCached = brightness
        gammaCached = gamma
        temperatureCached = temperature
        hueCached = hue
        shadowLiftCached = shadowLift
        sharpenCached = sharpness.coerceIn(0f, 0.8f)
        val prefs = AppPreferences.getShared(context)
        prefs.putSync(Anime4kKeys.SATURATION, saturation)
        prefs.putSync(Anime4kKeys.CONTRAST, contrast)
        prefs.putSync(Anime4kKeys.BRIGHTNESS, brightness)
        prefs.putSync(Anime4kKeys.GAMMA, gamma)
        prefs.putSync(Anime4kKeys.TEMPERATURE, temperature)
        prefs.putSync(Anime4kKeys.HUE, hue)
        prefs.putSync(Anime4kKeys.SHADOW_LIFT, shadowLift)
        prefs.putSync(Anime4kKeys.SHARPEN, sharpenCached)
        prefs.putSync(Anime4kKeys.PRESET, PicturePreset.Custom.name)
    }

    /** 当前预设（读取持久化记录） */
    fun currentPreset(context: Context): PicturePreset =
        PicturePreset.byNameOrCustom(AppPreferences.getShared(context).getString(Anime4kKeys.PRESET, ""))

    /** 读取开关偏好（设置页用；运行时以 [load] 缓存为准） */
    fun isEnabledPref(context: Context): Boolean =
        AppPreferences.getShared(context).getBoolean(Anime4kKeys.ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        enabledCached = enabled
        AppPreferences.getShared(context).putSync(Anime4kKeys.ENABLED, enabled)
    }

    fun isDeblurPref(context: Context): Boolean =
        AppPreferences.getShared(context).getBoolean(Anime4kKeys.DEBLUR, false)

    fun setDeblur(context: Context, enabled: Boolean) {
        deblurCached = enabled
        AppPreferences.getShared(context).putSync(Anime4kKeys.DEBLUR, enabled)
    }
}

/** 构建结果状态：着色器编译失败后本次进程内降级为直通，避免反复重试卡顿 */
object Anime4kStatus {

    @Volatile
    private var failed = false

    /** 最近一次成功构建的着色器 pass 数（0 表示未构建/失败） */
    @Volatile
    var lastPassCount = 0

    /** 最近一次成功绘制增强帧的时间（elapsedRealtime，0 表示尚未绘制） */
    @Volatile
    var lastDrawAt = 0L

    fun unavailable(): Boolean = failed

    fun onBuildSucceeded(passCount: Int) {
        failed = false
        lastPassCount = passCount
    }

    fun onBuildFailed() {
        failed = true
        lastPassCount = 0
        lastDrawAt = 0L
    }

    /** 效果关闭时重置状态，避免界面展示上一次的生效信息 */
    fun onDisabled() {
        failed = false
        lastPassCount = 0
        lastDrawAt = 0L
    }

    fun onFrameDrawn() {
        lastDrawAt = SystemClock.elapsedRealtime()
    }

    /** 最近 3 秒内绘制过增强帧，说明效果链确实在运行 */
    fun isEffective(): Boolean = lastDrawAt > 0L &&
        SystemClock.elapsedRealtime() - lastDrawAt < 3_000L

    /**
     * 当前是否由 FFmpeg 软解直写 Surface 渲染。
     * 该路径不经过 VideoSink，视频效果链不会被调用，增强必然不生效。
     */
    fun softwareVideoActive(): Boolean {
        val last = ExperimentalFfmpegVideoRenderer.lastRenderAtMs
        return last > 0L && SystemClock.elapsedRealtime() - last < 2_000L
    }

    /**
     * 当前视频渲染器是否绕过 VideoSink（FFmpeg 软解直写 Surface）。
     * 与 [softwareVideoActive] 依赖的 2 秒时间窗不同，这里直接读已选中的渲染器，
     * 暂停或画面静止时判断同样准确，供界面决定是否禁用画面调节。
     */
    fun isSoftVideoRenderer(player: ExoPlayer?): Boolean {
        if (player == null) return softwareVideoActive()
        for (index in 0 until player.rendererCount) {
            if (player.getRendererType(index) != C.TRACK_TYPE_VIDEO) continue
            val renderer = player.getRenderer(index) ?: continue
            // 软解优先模式下硬解渲染器同样在列表里，只认当前被分配了轨道的那个
            if (renderer.getState() != Renderer.STATE_DISABLED) {
                return renderer is ExperimentalFfmpegVideoRenderer
            }
        }
        // 尚未起播、没有活动的视频渲染器时退回时间窗判断
        return softwareVideoActive()
    }
}
