package com.tvbox.kotlin.app

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.AppPreferencesHolder
import com.tvbox.kotlin.core.server.cast.CastBridge
import com.tvbox.kotlin.core.server.cast.model.CastData
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.UiResources
import com.tvbox.kotlin.feature.play.ui.PlayActivity
import dagger.hilt.android.HiltAndroidApp
import me.jessyan.autosize.AutoSizeConfig
import me.jessyan.autosize.unit.Subunits
import javax.inject.Inject

/**
 * 应用入口，Hilt 根节点。
 * 负责触发各核心模块的初始化：偏好加载、爬虫上下文、本地服务、字体、AutoSize 等。
 */
@HiltAndroidApp
class TvBoxApp : Application() {

    @Inject
    lateinit var preferences: AppPreferences

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 注册 core/ui 全局 Context（雷鸟/荐片等后台线程读取字符串资源需要，缺失会 IllegalStateException）
        UiResources.install(this)
        // 注册全局共享偏好实例，供 Activity 基类等非注入场景同步读取
        AppPreferences.setShared(preferences)
        // 注册 Holder 静态入口，供 Bean/工具类（HawkListHelper 等）静态访问偏好
        AppPreferencesHolder.install(preferences)
        putDefaultParams()
        initAutoSize()
        initCore()
        initCastServer()
    }

    /**
     * 写入首次启动默认配置（App.initParams：仅当 key 不存在时写入），
     */
    private fun putDefaultParams() {
        putDefault(AppConstants.HOME_SHOW_SOURCE, true)       // 站点显示: true=开启
        putDefault(AppConstants.HOME_REC, 1)                  // 推荐: 0=豆瓣热播, 1=站点推荐, 2=观看历史
        putDefault(AppConstants.HOME_NUM, 0)                  // 历史条数: 0=20条, 1=40条, ...
        putDefault(AppConstants.SHOW_PREVIEW, true)           // 详情页: true=预览模式, false=海报模式
        putDefault(AppConstants.PLAY_SCALE, 0)                // 画面缩放: 0=默认, ...
        putDefault(AppConstants.BACKGROUND_PLAY_TYPE, 0)      // 后台: 0=关闭, 1=开启, 2=画中画
        putDefault(AppConstants.HOME_LOCALE, 0)               // 语言: 0=中文, 1=英文
        putDefault(AppConstants.THEME_SELECT, 0)              // 主题: 0=奈飞, ...
        putDefault(AppConstants.SEARCH_VIEW, 1)               // 搜索展示: 0=文字列表, 1=缩略图
        putDefault(AppConstants.DOH_URL, 0)                   // 安全DNS: 0=关闭, ...
        putDefault(AppConstants.CAST_ENABLE, true)            // 投屏: true=允许接收投屏
    }

    private fun putDefault(key: String, value: Any?) {
        if (!preferences.contains(key)) {
            preferences.putSync(key, value)
        }
    }

    /**
     * 初始化 AutoSize 单位适配：
     * dp/sp 使用系统原生密度，仅 mm 单位按 1280×720 设计稿缩放，
     * 布局中的 vs_xx/ts_xx dimen 均为 mm 单位，依赖此配置完成适配。
     */
    private fun initAutoSize() {
        try {
            AutoSizeConfig.getInstance().setCustomFragment(true).unitsManager
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM)
        } catch (e: Throwable) {
            LOG.e(e)
        }
    }

    /** 在后台协程中顺序初始化各模块，避免阻塞主线程 */
    private fun initCore() {
        // 偏好已在注入时完成全量加载；此处触发爬虫上下文与本地服务
        try {
            // 注入主进程 Context 到 catvod 占位类，jar 内爬虫通过 Init.context() 获取
            com.github.catvod.Init.set(this)
        } catch (e: Throwable) {
            LOG.e(e)
        }
        // 加载本地 EPG 频道映射（epg_data.json），直播 EPG 匹配 logo/epgid 秒级可用
        // （缺失时 epgid 退化为频道名导致模板请求失败/匹配不到）
        // 放后台线程执行，避免冷启动时主线程同步 IO 阻塞首帧渲染
        Thread {
            try {
                com.tvbox.kotlin.core.common.util.EpgUtil.init(this)
            } catch (e: Throwable) {
                LOG.e(e)
            }
        }.start()
    }

    /** 投屏控制广播 action */
    companion object {
        private const val TAG = "TvBoxApp"

        const val CAST_ACTION_PAUSE = "com.tvbox.kotlin.cast.PAUSE"
        const val CAST_ACTION_RESUME = "com.tvbox.kotlin.cast.RESUME"
        const val CAST_ACTION_STOP = "com.tvbox.kotlin.cast.STOP"
        const val CAST_ACTION_SEEK = "com.tvbox.kotlin.cast.SEEK"

        @Volatile
        lateinit var instance: TvBoxApp
            private set

        /** 荐片 P2P 组件（libp2p.so）单例，懒加载 */
        @Volatile
        private var p2p: com.p2p.P2PClass? = null

        /** 当前荐片资源地址（切换/退出时先暂停删除旧任务） */
        @Volatile
        var burl: String = ""

        /** 获取荐片 P2P 组件，加载失败返回 null */
        fun getP2p(): com.p2p.P2PClass? = try {
            if (p2p == null) {
                p2p = com.p2p.P2PClass(
                    com.tvbox.kotlin.core.common.util.FileUtils.getExternalCachePath(instance)
                )
            }
            p2p
        } catch (e: Throwable) {
            LOG.e(e)
            null
        }
    }

    /**
     * 初始化投屏服务（接收端）。
     * 后台线程延迟 2 秒启动，确保网络已就绪；
     * 不启动该服务时本机无法被其他设备搜索到。
     * 仅当"允许接收投屏"开启时启动，关闭时本机不再被其他设备发现。
     */
    private fun initCastServer() {
        if (!preferences.get(AppConstants.CAST_ENABLE, true)) return
        val handler = Handler(Looper.getMainLooper())
        Thread {
            try {
                Thread.sleep(2000)
                val bridge = CastBridge.getInstance(this)
                bridge.startCastServer()
                bridge.setCastListener(object : CastBridge.CastListener {
                    override fun onReceivePlay(data: CastData?) {
                        val url = data?.url
                        if (url.isNullOrEmpty()) return
                        val title = data.title ?: applicationContext.getString(uiR.string.cast_video)
                        val position = data.position
                        handler.post {
                            Toast.makeText(this@TvBoxApp, applicationContext.getString(uiR.string.toast_cast_received, title), Toast.LENGTH_LONG).show()
                            startActivity(
                                Intent(this@TvBoxApp, PlayActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    putExtra(PlayActivity.EXTRA_URL, url)
                                    putExtra(PlayActivity.EXTRA_TITLE, title)
                                    putExtra(PlayActivity.EXTRA_POSITION, position)
                                    // 弹幕数据写临时文件传递（Intent 直传 1MB+ 字符串会超 Binder 事务限制导致崩溃）
                                    val danmuData = data.danmakuData
                                    if (!danmuData.isNullOrEmpty()) {
                                        try {
                                            val f = File(cacheDir, "cast_danmaku_${System.currentTimeMillis()}.xml")
                                            f.writeText(danmuData, Charsets.UTF_8)
                                            putExtra(PlayActivity.EXTRA_DANMU_FILE, f.absolutePath)
                                        } catch (e: Throwable) {
                                            LOG.e(TAG, "保存投屏弹幕文件失败", e)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    override fun onReceivePause() {
                        sendBroadcast(Intent(CAST_ACTION_PAUSE))
                    }

                    override fun onReceiveResume() {
                        sendBroadcast(Intent(CAST_ACTION_RESUME))
                    }

                    override fun onReceiveStop() {
                        sendBroadcast(Intent(CAST_ACTION_STOP))
                    }

                    override fun onReceiveSeek(position: Long) {
                        sendBroadcast(Intent(CAST_ACTION_SEEK).putExtra("position", position))
                    }

                    override fun onStatusRequested(): CastData = CastData()
                })
            } catch (e: Throwable) {
                LOG.e(TAG, "投屏服务启动失败", e)
            }
        }.start()
    }
}
