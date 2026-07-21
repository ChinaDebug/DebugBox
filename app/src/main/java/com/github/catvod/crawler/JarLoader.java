
package com.github.catvod.crawler;

import android.content.Context;
import android.os.Build;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;

public class JarLoader {
    private final ConcurrentHashMap<String, DexClassLoader> classLoaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> proxyMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private volatile String recentJarKey = "";

    /**
     * 不要在主线程调用我
     *
     * @param cache
     */
    public boolean load(String cache) {
        recentJarKey = "main";
        return loadClassLoader(cache, recentJarKey);
    }

    public void clear() {
        spiders.clear();
        proxyMethods.clear();
        classLoaders.clear();
    }

    private boolean makeJarReadOnly(String jarPath) {
        // Android 14+ 动态加载代码要求被加载文件必须为只读，否则系统会抛出异常
        if (Build.VERSION.SDK_INT < 34) {
            return true;
        }
        File jarFile = new File(jarPath);
        if (!jarFile.exists()) {
            return false;
        }
        boolean result = jarFile.setReadOnly();
        if (!result) {
            // setReadOnly 失败时尝试通过显式关闭写权限达到只读效果
            result = jarFile.setWritable(false, false) && jarFile.setReadable(true, false);
        }
        if (!result) {
            LOG.e("设置 jar 只读失败: " + jarPath);
        }
        return result;
    }

    private boolean loadClassLoader(String jar, String key) {
        if (classLoaders.containsKey(key)){
            return true;
        }
        boolean success = false;
        try {
            makeJarReadOnly(jar);
            File cacheDir = new File(App.getInstance().getCacheDir().getAbsolutePath() + "/catvod_csp");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            final DexClassLoader classLoader = new DexClassLoader(jar, cacheDir.getAbsolutePath(), null, App.getInstance().getClassLoader());
            try {
                Class<?> classInit = classLoader.loadClass("com.github.catvod.spider.Init");
                if (classInit != null) {
                    Method initMethod = classInit.getMethod("init", Context.class);
                    initMethod.invoke(null, App.getInstance());
                    success = true;
                    try {
                        Class<?> proxy = classLoader.loadClass("com.github.catvod.spider.Proxy");
                        Method proxyMethod = proxy.getMethod("proxy", Map.class);
                        proxyMethods.put(key, proxyMethod);
                    } catch (Throwable th) {
                        LOG.e(th);
                    }
                }
            } catch (Throwable th) {
                LOG.e(th);
            }

            if (success) {
                classLoaders.put(key, classLoader);
            }
        } catch (Throwable th) {
            LOG.e(th);
        }
        return success;
    }

    private DexClassLoader loadJarInternal(String jar, String md5, String key) {
        if (classLoaders.containsKey(key)){
            return classLoaders.get(key);
        }
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp/" + key + ".jar");
        if (!md5.isEmpty()) {
            if (cache.exists() && MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                if(loadClassLoader(cache.getAbsolutePath(), key)){
                    return classLoaders.get(key);
                }else {
                    return null;
                }
            }
        }else {
            if (cache.exists() && !FileUtils.isWeekAgo(cache)) {
                if(loadClassLoader(cache.getAbsolutePath(), key)){
                    return classLoaders.get(key);
                }
            }
        }
        try {
            Response response = OkGo.<File>get(jar).execute();
            if (response.body() == null) {
                return null;
            }
            try (InputStream is = response.body().byteStream();
                 OutputStream os = new FileOutputStream(cache)) {
                byte[] buffer = new byte[2048];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
            }
            loadClassLoader(cache.getAbsolutePath(), key);
            return classLoaders.get(key);
        } catch (Throwable e) {
            LOG.e(e);
        }
        return null;
    }

    public Spider getSpider(String key, String cls, String ext, String jar) {
        if (spiders.containsKey(key)) {
            return spiders.get(key);
        }
        if (cls == null) {
            return new SpiderNull();
        }
        String clsKey = cls.replace("csp_", "");
        String jarUrl = "";
        String jarMd5 = "";
        String jarKey;
        if (jar.isEmpty()) {
            jarKey = "main";
        } else {
            String[] urls = jar.split(";md5;");
            jarUrl = urls[0];
            jarKey = MD5.string2MD5(jarUrl);
            jarMd5 = urls.length > 1 ? urls[1].trim() : "";
        }
        recentJarKey = jarKey;
        DexClassLoader classLoader = jarKey.equals("main")? classLoaders.get("main"):loadJarInternal(jarUrl, jarMd5, jarKey);
        if (classLoader == null) {
            return new SpiderNull();
        }
        try {
            Class<?> spiderClass = classLoader.loadClass("com.github.catvod.spider." + clsKey);
            Spider sp = (Spider) spiderClass.getDeclaredConstructor().newInstance();
            sp.init(App.getInstance(), ext);
            if (!jar.isEmpty()) {
                sp.homeContent(false);
            }
            spiders.put(key, sp);
            return sp;
        } catch (Throwable th) {
            LOG.e(th);
        }
        return new SpiderNull();
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            if (classLoader == null) {
                return new JSONObject();
            }
            String clsKey = "Json" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            Class<?> jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, url);
        } catch (Throwable th) {
            LOG.e(th);
        }
        return new JSONObject();
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            if (classLoader == null) {
                return new JSONObject();
            }
            String clsKey = "Mix" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            Class<?> jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, name, flag, url);
        } catch (Throwable th) {
            LOG.e(th);
        }
        return new JSONObject();
    }

    public Object[] proxyInvoke(Map<String,String> params) {
        try {
            Method proxyFun = proxyMethods.get(recentJarKey);
            if (proxyFun != null) {
                return (Object[]) proxyFun.invoke(null, params);
            }
        } catch (Throwable th) {
        }
        return null;
    }
}
