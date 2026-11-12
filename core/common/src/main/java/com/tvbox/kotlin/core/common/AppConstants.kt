package com.tvbox.kotlin.core.common

/**
 * 全局常量与偏好键定义。
 */
object AppConstants {

    const val TAG = "TVBox"

    /** 默认点播接口订阅地址 */
    const val DEFAULT_API_URL = ""

    /** 本地 HTTP 服务端口 */
    const val LOCAL_SERVER_PORT = 9978

    /** 用户代理（移动端） */
    const val UA_DEFAULT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 桌面端 UA：PC 解析站普遍拒绝手机 UA */
    const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 请求超时（秒） */
    const val TIMEOUT_CONNECT = 15
    const val TIMEOUT_READ = 30
    const val TIMEOUT_WRITE = 30

    // ---------- 偏好键 ----------
    const val API_URL = "api_url"
    const val API_HISTORY = "api_history"
    /** 订阅地址为多仓（接口合集 {"urls":[...]}）时解析出的线路列表（JSON 序列化） */
    const val API_LINE_LIST = "api_line_list"
    /** 多仓线路列表对应的来源订阅地址；删除该来源历史时据此同步清除线路列表 */
    const val API_LINE_SOURCE = "api_line_source"
    /** 多仓线路列表按来源订阅地址映射存储（JSON: Map<订阅地址, 线路列表>），支持多仓源共存 */
    const val API_LINE_MAP = "api_line_map"
    const val LIVE_URL = "live_url"
    const val LIVE_HISTORY = "live_history"
    const val EPG_URL = "epg_url"
    const val EPG_HISTORY = "epg_history"
    const val LOGO_URL = "logo_url"
    /** web 订阅设置"提交全部并重载"待应用标记：首页不在前台时事件会丢，onResume 据此兜底重载 */
    const val RELOAD_PENDING = "reload_pending"

    const val HOME_API = "home_api"
    const val HOME_REC = "home_rec"

    /** 播放进度缓存键前缀，完整键为 前缀 + 地址(或 progressKey)的 MD5；*/
    const val VOD_PROGRESS_KEY_PREFIX = "vod_progress_v1_"
    const val HOME_REC_STYLE = "home_rec_style"
    const val HOME_NUM = "home_num"
    const val HOME_SHOW_SOURCE = "show_source"
    const val HOME_LOCALE = "language"
    const val HOME_DEFAULT_SHOW = "home_default_show"

    const val SHOW_PREVIEW = "show_preview"
    const val PLAY_TYPE = "play_type"
    const val PLAY_RENDER = "play_render"
    const val PLAY_SCALE = "play_scale"
    const val PLAY_TIME_STEP = "play_time_step"
    /** 片头/片尾记忆键前缀，完整键为 前缀 + 源key|影片id（跨集共享） */
    const val PLAY_TIME_START = "play_time_start_"
    const val PLAY_TIME_END = "play_time_end_"
    const val VIDEO_PURIFY = "video_purify"
    const val EXO_RENDERER_MODE = "exo_renderer_mode"
    const val VOD_PLAYER_PREFERRED = "vod_player_preferred"

    const val DOH_URL = "doh_url"
    const val DEFAULT_PARSE = "parse_default"
    const val SEARCH_VIEW = "search_view"
    const val SOURCES_FOR_SEARCH = "checked_sources_for_search"
    const val STORAGE_DRIVE_SORT = "storage_drive_sort"
    const val SUBTITLE_TEXT_SIZE = "subtitle_text_size"
    const val SUBTITLE_TEXT_COLOR = "subtitle_text_color"
    const val SUBTITLE_STROKE_COLOR = "subtitle_stroke_color"
    const val SUBTITLE_TEXT_BOLD = "subtitle_text_bold"
    const val SUBTITLE_TIME_DELAY = "subtitle_time_delay"
    const val THEME_SELECT = "theme_select"
    const val WALLPAPER_BLUR = "wallpaper_blur"
    /** 是否允许接收投屏（投屏接收端服务开关） */
    const val CAST_ENABLE = "cast_enable"
    /** 投屏设备名（投屏接收端对外展示名称） */
    const val CAST_DEVICE_NAME = "cast_device_name"
    const val BACKGROUND_PLAY_TYPE = "background_play_type"
    const val FAST_SEARCH_MODE = "fast_search_mode"
    const val SCREEN_DISPLAY_NET_SPEED = "screen_display_net_speed"
    const val SCREEN_DISPLAY_SEEK_TIME = "screen_display_seek_time"
    const val SCREEN_DISPLAY_SYS_TIME = "screen_display_sys_time"
    const val SCREEN_DISPLAY_BATTERY = "screen_display_battery"

    const val LIVE_CHANNEL = "last_live_channel_name"
    const val LIVE_CHANNEL_GROUP = "last_live_channel_group_name"
    const val LIVE_CHANNEL_SOURCE = "last_live_channel_source_index"
    const val LIVE_CHANNEL_REVERSE = "live_channel_reverse"
    const val LIVE_CROSS_GROUP = "live_cross_group"
    const val LIVE_CONNECT_TIMEOUT = "live_connect_timeout"
    const val LIVE_SHOW_NET_SPEED = "live_show_net_speed"
    const val LIVE_SHOW_TIME = "live_show_time"
    const val LIVE_SKIP_PASSWORD = "live_skip_password"
}
