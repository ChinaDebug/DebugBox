package com.github.catvod.crawler;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import androidx.core.content.ContextCompat;

import com.github.catvod.crawler.python.IPyLoader;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.MD5;
import com.undcover.freedom.pyramid.PythonLoader;
import com.undcover.freedom.pyramid.PythonSpider;

import org.greenrobot.eventbus.EventBus;

import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class pyLoader implements IPyLoader {
    private PythonLoader pythonLoader;
    private final ConcurrentHashMap<String, Spider> spiders;
    private String lastConfig = null;
    private volatile boolean isInitialized = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public pyLoader() {
        spiders = new ConcurrentHashMap<>();
        // 延迟初始化到后台线程，避免 ANR
        executor.execute(() -> {
            try {
                pythonLoader = PythonLoader.getInstance().setApplication(App.getInstance());
                isInitialized = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public void clear() {
        spiders.clear();
    }

    @Override
    public void setConfig(String jsonStr) {
        if (!isInitialized || pythonLoader == null) return;
        if (jsonStr != null && !jsonStr.equals(lastConfig)) {
            pythonLoader.setConfig(jsonStr);
            lastConfig = jsonStr;
        }
    }

    private String recentPyApi;
    @Override
    public void setRecentPyKey(String pyApi) {
        recentPyApi = pyApi;
    }

    @Override
    public Spider getSpider(String key, String cls, String ext) {
        if (spiders.containsKey(key)) {
            return spiders.get(key);
        }
        if (!isInitialized || pythonLoader == null) {
            return new SpiderNull();
        }
        try {
            if (!hasStoragePermission()) {
                // 发送权限不足事件，通知UI层提示用户
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_STORAGE_PERMISSION_DENIED));
                return new SpiderNull();
            }
            Spider sp = pythonLoader.getSpider(key, getPyUrl(cls, ext));
            spiders.put(key, sp);
            return sp;
        } catch (Throwable th) {
        }
        return new SpiderNull();
    }

    private boolean hasStoragePermission() {
        // Android 11+ (API 30+) 检查 MANAGE_EXTERNAL_STORAGE 特殊权限
        // 注意：只有当应用的 targetSdkVersion >= 30 时才需要检查此权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && App.getInstance().getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        // Android 10 及以下，或者 targetSdkVersion < 30 的应用使用传统存储权限
        return ContextCompat.checkSelfPermission(App.getInstance(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public Object[] proxyInvoke(Map<String, String> params){
        if(recentPyApi==null || !isInitialized || pythonLoader == null) {
            return new Object[]{"error", "PyLoader not initialized"};
        }
        try {
            PythonSpider originalSpider = (PythonSpider) getSpider(MD5.string2MD5(recentPyApi), recentPyApi,"");
            return originalSpider.proxyLocal(params);
        } catch (Throwable th) {
        }
        return new Object[]{"error", "Proxy method not found"};
    }

    private String getPyUrl(String api, String ext) throws UnsupportedEncodingException {
        StringBuilder urlBuilder = new StringBuilder(api);
        if (!ext.isEmpty()) {
            urlBuilder.append(api.contains("?") ? "&" : "?").append("extend=").append(ext);
        }
        return urlBuilder.toString();
    }
}
