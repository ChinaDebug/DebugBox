package com.github.tvbox.osc.base;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.os.HandlerCompat;
import androidx.multidex.MultiDexApplication;

import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.cast.CastBridge;
import com.github.tvbox.osc.cast.model.CastData;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LocaleHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;

import com.hjq.permissions.XXPermissions;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;

import java.io.File;
import java.util.ArrayList;

import io.github.inflationx.calligraphy3.CalligraphyConfig;
import io.github.inflationx.calligraphy3.CalligraphyInterceptor;
import io.github.inflationx.viewpump.ViewPump;
import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;
    private static P2PClass p;
    public static String burl;
    private static String dashData;
    public static ViewPump viewPump = null;
    private final Handler handler;
    private static Thread initThread;

    public App() {
        instance = this;
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        initParams();
        initLocale();
        
        // 关键修复：在主线程中初始化 ControlManager，确保 HomeActivity 启动时已经初始化完成
        ControlManager.init(App.this);

        // 初始化投屏服务（接收端）
        initCastServer();

        // 初始化 AutoSize
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        
        if (initThread == null || !initThread.isAlive()) {
            initThread = new Thread(() -> {
                try {
                    OkGoHelper.init();
                    XXPermissions.setCheckMode(false);
                    EpgUtil.init();
                    AppDataManager.init();
                    PlayerHelper.init();
                    QuickJSLoader.init();
                    FileUtils.cleanPlayerCache();
                    initFontSupport();
                } catch (Exception e) {
                }
            });
            initThread.setDaemon(true);
            initThread.start();
        }
        
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
    }

    private void initFontSupport() {
        // Android 11+ (API 30+) 检查 MANAGE_EXTERNAL_STORAGE 特殊权限
        // 注意：只有当应用的 targetSdkVersion >= 30 时才需要检查此权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                return;
            }
        } else {
            // Android 10 及以下，或者 targetSdkVersion < 30 的应用使用传统存储权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        String extStorageDir = Environment.getExternalStorageDirectory().getAbsolutePath();
        File fontFile = new File(extStorageDir + "/tvbox.ttf");
        if (fontFile.exists()) {
            viewPump = ViewPump.builder()
                    .addInterceptor(new CalligraphyInterceptor(
                            new CalligraphyConfig.Builder()
                                    .setDefaultFontPath(fontFile.getAbsolutePath())
                                    .setFontAttrId(R.attr.fontPath)
                                    .build()))
                    .build();
        }
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }


    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        Hawk.put(HawkConfig.DEBUG_OPEN, false);

        // 首页选项
        putDefault(HawkConfig.HOME_SHOW_SOURCE, true);       //数据源显示: true=开启, false=关闭
        putDefault(HawkConfig.HOME_SEARCH_POSITION, false);  //按钮位置-搜索: true=上方, false=下方
        putDefault(HawkConfig.HOME_MENU_POSITION, true);     //按钮位置-设置: true=上方, false=下方
        putDefault(HawkConfig.HOME_REC, 1);                  //推荐: 0=豆瓣热播, 1=站点推荐, 2=观看历史

        //历史选项
        putDefault(HawkConfig.HOME_NUM, 0);                  //历史条数: 0=20条, 1=40条, 2=60条, 3=80条, 4=100条
        
        
        // 播放选项
        putDefault(HawkConfig.SHOW_PREVIEW, true);           //详情页: true=预览模式, false=海报模式
        putDefault(HawkConfig.PLAY_SCALE, 0);                //画面缩放: 0=默认, 1=16:9, 2=4:3, 3=填充, 4=原始, 5=裁剪
        putDefault(HawkConfig.BACKGROUND_PLAY_TYPE, 0);      //后台：0=关闭, 1=开启, 2=画中画
        putDefault(HawkConfig.IJK_CODEC, "硬解码");           //IJK解码: 软解码, 硬解码
        putDefault(HawkConfig.VOD_PLAYER_PREFERRED, 0);      //点播首选播放器: 0=配置地址指定, 1=系统, 2=IJK, 3=Exo, 4=阿里, 5=MX, 6=Reex, 7=Kodi
        
        // 系统选项
        putDefault(HawkConfig.HOME_LOCALE, 0);               //语言: 0=中文, 1=英文
        putDefault(HawkConfig.THEME_SELECT, 0);              //主题: 0=奈飞, 1=哆啦, 2=百事, 3=鸣人, 4=小黄, 5=八神, 6=樱花
        putDefault(HawkConfig.SEARCH_VIEW, 1);               //搜索展示: 0=文字列表, 1=缩略图
        putDefault(HawkConfig.DOH_URL, 0);                   //安全DNS: 0=关闭, 1=腾讯, 2=阿里, 3=360, 4=Google, 5=AdGuard, 6=Quad9

    }

    private void initLocale() {
        if (Hawk.get(HawkConfig.HOME_LOCALE, 0) == 0) {
            LocaleHelper.setLocale(App.this, "zh");
        } else {
            LocaleHelper.setLocale(App.this, "");
        }
    }

    public static App getInstance() {
        return instance;
    }

    public static void cleanup() {
        if (initThread != null && initThread.isAlive()) {
            initThread.interrupt();
        }
    }

    private void putDefault(String key, Object value) {
        if (!Hawk.contains(key)) {
            Hawk.put(key, value);
        }
    }

    /**
     * 初始化投屏服务（接收端）
     * 在后台线程中启动，避免阻塞主线程
     */
    private void initCastServer() {
        new Thread(() -> {
            try {
                Thread.sleep(2000); // 延迟2秒启动，确保网络已准备好
                CastBridge castBridge = CastBridge.getInstance(App.this);
                castBridge.startCastServer();
                // 设置投屏接收监听器
                castBridge.setCastListener(new CastBridge.CastListener() {
                    @Override
                    public void onReceivePlay(CastData data) {
                        try {
                            if (data == null) {
                                return;
                            }
                            String url = data.getUrl();
                            String title = data.getTitle();
                            int playerType = data.getPlayerType(); // 获取发送端指定的播放器类型
                            if (url != null && !url.isEmpty()) {
                                final String finalUrl = url;
                                final String finalTitle = title != null ? title : "投屏视频";
                                final int finalPosition = (int) data.getPosition();
                                final int finalPlayerType = playerType;
                                // 获取弹幕数据
                                final String finalDanmakuData = data.getDanmakuData();
                                final boolean finalHasDanmaku = data.getHasDanmaku();
                                // 在主线程显示提示并启动播放
                                handler.post(() -> {
                                    try {
                                        Toast.makeText(App.this, "收到投屏: " + finalTitle, Toast.LENGTH_LONG).show();

                                        // 启动播放页面
                                        Intent playIntent = new Intent(App.this, com.github.tvbox.osc.ui.activity.PlayActivity.class);
                                        playIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                        playIntent.putExtra("url", finalUrl);
                                        playIntent.putExtra("title", finalTitle);
                                        playIntent.putExtra("position", finalPosition);
                                        playIntent.putExtra("playerType", finalPlayerType); // 传递播放器类型
                                        // 传递弹幕数据（通过临时文件，避免Intent大小限制）
                                        if (finalHasDanmaku && finalDanmakuData != null && !finalDanmakuData.isEmpty()) {
                                            try {
                                                // 保存弹幕数据到临时文件
                                                File danmakuFile = new File(getCacheDir(), "cast_danmaku_" + System.currentTimeMillis() + ".xml");
                                                FileUtils.writeSimple(finalDanmakuData.getBytes("UTF-8"), danmakuFile);
                                                playIntent.putExtra("danmakuFilePath", danmakuFile.getAbsolutePath());
                                                playIntent.putExtra("hasDanmaku", true);
                                            } catch (Exception e) {
                                                LOG.e("App", "保存弹幕数据到临时文件失败: " + e.getMessage());
                                            }
                                        }
                                        startActivity(playIntent);
                                    } catch (Exception e) {
                                        LOG.e("App", "启动播放页面失败: " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                });
                            }
                        } catch (Exception e) {
                            LOG.e("App", "处理投屏请求异常: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onReceivePause() {
                        sendBroadcast(new Intent("com.github.tvbox.osc.CAST_PAUSE"));
                    }

                    @Override
                    public void onReceiveResume() {
                        sendBroadcast(new Intent("com.github.tvbox.osc.CAST_RESUME"));
                    }

                    @Override
                    public void onReceiveStop() {
                        LOG.d("App", "收到投屏停止请求");
                        sendBroadcast(new Intent("com.github.tvbox.osc.CAST_STOP"));
                    }

                    @Override
                    public void onReceiveSeek(long position) {
                        Intent intent = new Intent("com.github.tvbox.osc.CAST_SEEK");
                        intent.putExtra("position", position);
                        sendBroadcast(intent);
                    }

                    @Override
                    public CastData onStatusRequested() {
                        // 发送广播请求当前播放状态
                        Intent intent = new Intent("com.github.tvbox.osc.CAST_STATUS_REQUEST");
                        sendBroadcast(intent);
                        return new CastData();
                    }
                });
            } catch (Exception e) {
                LOG.e("App", "投屏服务启动失败: " + e.getMessage());
            }
        }).start();
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
        CastBridge.release();
    }

    public void setDashData(String data) {
        dashData = data;
    }

    public String getDashData() {
        return dashData;
    }

    public static void post(Runnable runnable) {
        getInstance().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        getInstance().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) getInstance().handler.postDelayed(runnable, delayMillis);
    }
}
