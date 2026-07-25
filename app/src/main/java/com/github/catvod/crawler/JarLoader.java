package com.github.catvod.crawler;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;

/**
 * 爬虫 jar 加载器。
 * 负责按 key 管理 DexClassLoader、注入 SpiderApi、Proxy 端口、弹幕回调方法，
 * 并支持 http/file/assets/clan 多协议 jar 加载与受保护 jar 的兼容初始化。
 */
public class JarLoader {

    private static final String TAG = "JarLoader";
    private static final String MAIN_KEY = "main";

    private final ConcurrentHashMap<String, DexClassLoader> loaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> proxyMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> danmuClickMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> danmuLongClickMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> siteJarKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> aliases = new ConcurrentHashMap<>();
    private final ProtectedInitJar protectedInitJar = new ProtectedInitJar();
    private volatile String recent = MAIN_KEY;

    /**
     * 主 jar 加载入口，保留原签名以兼容 ApiConfig 调用。
     * 不要在主线程调用。
     */
    public boolean load(String cache) {
        boolean success = load(MAIN_KEY, new File(cache));
        if (success) recent = MAIN_KEY;
        return success;
    }

    /** 设置最近使用的 jar key，用于代理与弹幕方法定位 */
    public void setRecentJarKey(String key) {
        if (TextUtils.isEmpty(key)) return;
        recent = realKey(key);
        injectProxyPort(loaders.get(recent));
    }

    /** 直播 jar 加载，jar 字符串可包含 ;md5; 校验信息 */
    public void loadLiveJar(String jar) {
        String key = jarKey(jar);
        parseJar(key, jar);
        setRecentJarKey(key);
    }

    public void clear() {
        for (Spider spider : spiders.values()) {
            try {
                spider.destroy();
            } catch (Throwable ignored) {
            }
        }
        loaders.clear();
        proxyMethods.clear();
        danmuClickMethods.clear();
        danmuLongClickMethods.clear();
        spiders.clear();
        locks.clear();
        siteJarKeys.clear();
        aliases.clear();
        protectedInitJar.clear();
        recent = MAIN_KEY;
    }

    private boolean load(String key, File file) {
        if (Thread.interrupted()) return false;
        if (!exists(file)) return false;
        if (loaders.containsKey(key)) return true;
        try {
            makeJarReadOnly(file.getAbsolutePath());
            String cachePath = jarDir().getAbsolutePath();
            DexClassLoader loader = new DexClassLoader(file.getAbsolutePath(), cachePath, cachePath, App.getInstance().getClassLoader());
            invokeInit(loader, file.getAbsolutePath());
            invokeProxy(key, loader);
            invokeDanmaku(key, loader);
            injectProxyPort(loader);
            loaders.put(key, loader);
            LOG.i(TAG, "load success key=" + key + ", file=" + file.getAbsolutePath());
            return true;
        } catch (Throwable e) {
            LOG.e(TAG, "load error key=" + key + ", msg=" + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Android 14+ 动态加载代码要求被加载文件必须为只读，否则系统会抛出异常。
     * 低版本直接返回 true 以兼容旧行为。
     */
    private boolean makeJarReadOnly(String jarPath) {
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
            LOG.e(TAG, "设置 jar 只读失败: " + jarPath);
        }
        return result;
    }

    /** 调用 jar 内 Init.init(Context)，受保护 jar 走 ProtectedInitJar 反射初始化 */
    private void invokeInit(DexClassLoader loader, String jar) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Init");
            Method method = clz.getMethod("init", Context.class);
            if (protectedInitJar.check(jar)) {
                protectedInitJar.init(clz);
            } else {
                method.invoke(null, App.getInstance());
            }
        } catch (Throwable e) {
            LOG.e(TAG, "invokeInit error", e);
        }
    }

    /** 反射获取 jar 内 Proxy.proxy(Map) 静态方法，用于代理请求分发 */
    private void invokeProxy(String key, DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Proxy");
            Method method = clz.getMethod("proxy", Map.class);
            proxyMethods.put(key, method);
        } catch (Throwable ignored) {
            // 部分自实现 jar 不提供 Proxy 类属正常情况，忽略即可
        }
    }

    /** 反射获取 jar 内 Danmaku 类的 onClick / onLongClick 方法，用于弹幕搜索 UI 联动 */
    private void invokeDanmaku(String key, DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Danmaku");
            try {
                danmuClickMethods.put(key, clz.getMethod("onClick", String.class, String.class));
            } catch (Throwable ignored) {
            }
            try {
                danmuLongClickMethods.put(key, clz.getMethod("onLongClick", String.class, String.class));
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 解析并加载 jar，支持 ;md5; 校验以及 http/file/assets/clan 协议 */
    public void parseJar(String key, String jar) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(jar)) return;
        if (loaders.containsKey(key)) return;
        Object lock = lock(key);
        synchronized (lock) {
            if (loaders.containsKey(key)) return;
            String source = jar;
            String md5 = "";
            String[] texts = jar.split(";md5;");
            if (texts.length > 1) {
                source = texts[0];
                md5 = texts[1].trim();
            }
            aliases.put(jarKey(source), key);
            if (md5.startsWith("http")) {
                String value = OkHttp.string(md5);
                md5 = value == null ? "" : value.trim();
            }
            File file = fileForJar(source);
            if (!TextUtils.isEmpty(md5) && exists(file) && MD5.getFileMd5(file).equalsIgnoreCase(md5)) {
                load(key, file);
            } else if (TextUtils.isEmpty(md5) && exists(file) && !FileUtils.isWeekAgo(file)) {
                load(key, file);
            } else if (source.startsWith("http")) {
                load(key, download(source, file));
            } else if (source.startsWith("assets")) {
                load(key, copyAsset(source, file));
            } else if (source.startsWith("file")) {
                load(key, local(source));
            } else if (source.startsWith("clan://")) {
                load(key, download(clanToAddress(source), file));
            }
        }
    }

    /** 直接获取 DexClassLoader，供外部使用反射加载其他类 */
    public DexClassLoader dex(String jar) {
        try {
            String key = jarKey(jar);
            parseJar(key, jar);
            return loaders.get(key);
        } catch (Throwable e) {
            LOG.e(TAG, "dex error", e);
            return null;
        }
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        key = key == null ? "" : key;
        api = api == null ? "" : api;
        ext = ext == null ? "" : ext;
        jar = jar == null ? "" : jar;
        if (TextUtils.isEmpty(api)) return new SpiderNull();

        String jaKey = TextUtils.isEmpty(jar) ? MAIN_KEY : jarKey(jar);
        String spKey = jaKey + key;
        recent = jaKey;
        siteJarKeys.put(key, jaKey);
        injectProxyPort(loaders.get(jaKey));

        Spider cached = spiders.get(spKey);
        if (cached != null) {
            LOG.i(TAG, "getSpider cached key=" + spKey);
            return cached;
        }

        try {
            if (!MAIN_KEY.equals(jaKey)) parseJar(jaKey, jar);
            DexClassLoader loader = loaders.get(jaKey);
            if (loader == null) return new SpiderNull();
            Spider spider = (Spider) loader.loadClass("com.github.catvod.spider." + className(api)).getDeclaredConstructor().newInstance();
            spider.siteKey = key;
            spider.initApi(new SpiderApi());
            spider.init(App.getInstance(), ext);
            spiders.put(spKey, spider);
            LOG.i(TAG, "getSpider success key=" + spKey);
            return spider;
        } catch (Throwable e) {
            LOG.e(TAG, "getSpider error key=" + spKey + ", msg=" + e.getMessage(), e);
            return new SpiderNull();
        }
    }

    /** 调用当前 jar 提供的弹幕搜索 UI 方法，longClick 区分长按场景 */
    public void searchDanmuUi(String name, String episode, boolean longClick) {
        try {
            ConcurrentHashMap<String, Method> methods = longClick ? danmuLongClickMethods : danmuClickMethods;
            Method method = methods.get(recent);
            if (method == null) method = methods.get(MAIN_KEY);
            if (method == null) return;
            method.invoke(null, name, episode);
        } catch (Throwable e) {
            LOG.e(TAG, "searchDanmuUi error", e);
        }
    }

    /** 当前 jar 是否提供弹幕搜索 UI 方法 */
    public boolean hasDanmuSearchUi() {
        return danmuClickMethods.containsKey(recent) || danmuLongClickMethods.containsKey(recent);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        try {
            Class<?> clz = loadParserClass("com.github.catvod.parser.Json" + key);
            Method method = clz.getMethod("parse", LinkedHashMap.class, String.class);
            return (JSONObject) method.invoke(null, jxs, url);
        } catch (Throwable e) {
            LOG.e(TAG, "jsonExt error", e);
            return new JSONObject();
        }
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        try {
            Class<?> clz = loadParserClass("com.github.catvod.parser.Mix" + key);
            Method method = clz.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            return (JSONObject) method.invoke(null, jxs, name, flag, url);
        } catch (Throwable e) {
            LOG.e(TAG, "jsonExtMix error", e);
            return new JSONObject();
        }
    }

    /**
     * 代理调用入口，按 siteKey 关联的 jar → 最近 jar → 其余 jar 顺序回退匹配，
     * 兼容多源聚合场景。
     */
    public Object[] proxyInvoke(Map<String, String> params) {
        String siteKey = params == null ? null : params.get("siteKey");
        if (!TextUtils.isEmpty(siteKey)) {
            Object[] result = proxyInvoke(proxyMethods.get(siteJarKeys.get(siteKey)), params);
            if (result != null) return result;
        }
        Object[] result = proxyInvoke(proxyMethods.get(recent), params);
        if (result != null) return result;
        for (Map.Entry<String, Method> entry : proxyMethods.entrySet()) {
            if (entry.getKey().equals(recent)) continue;
            result = proxyInvoke(entry.getValue(), params);
            if (result != null) return result;
        }
        return null;
    }

    private Object[] proxyInvoke(Method method, Map<String, String> params) {
        try {
            return method == null ? null : (Object[]) method.invoke(null, params);
        } catch (Throwable e) {
            LOG.e(TAG, "proxyInvoke error", e);
            return null;
        }
    }

    private Class<?> loadParserClass(String name) throws ClassNotFoundException {
        DexClassLoader loader = loaders.get(recent);
        if (loader != null) {
            try {
                return loader.loadClass(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        loader = loaders.get(MAIN_KEY);
        if (loader != null) return loader.loadClass(name);
        throw new ClassNotFoundException(name);
    }

    private File download(String url, File file) {
        InputStream is = null;
        FileOutputStream os = null;
        try {
            Response response = OkGo.<File>get(url).execute();
            if (response.body() == null) return file;
            is = response.body().byteStream();
            os = new FileOutputStream(create(file));
            byte[] buffer = new byte[16384];
            int length;
            while ((length = is.read(buffer)) != -1) {
                if (Thread.interrupted()) return file;
                os.write(buffer, 0, length);
            }
            os.flush();
        } catch (Throwable e) {
            LOG.e(TAG, "download error", e);
        } finally {
            close(is);
            close(os);
        }
        return file;
    }

    private File copyAsset(String url, File file) {
        InputStream is = null;
        FileOutputStream os = null;
        try {
            String path = url.replace("assets://", "").replace("assets/", "");
            is = App.getInstance().getAssets().open(path);
            os = new FileOutputStream(create(file));
            byte[] buffer = new byte[16384];
            int length;
            while ((length = is.read(buffer)) != -1) {
                os.write(buffer, 0, length);
            }
            os.flush();
        } catch (Throwable e) {
            LOG.e(TAG, "copyAsset error", e);
        } finally {
            close(is);
            close(os);
        }
        return file;
    }

    private File local(String path) {
        path = path.replace("file:/", "");
        File file = new File(Environment.getExternalStorageDirectory(), path);
        return file.exists() ? file : new File(path);
    }

    private File fileForJar(String jar) {
        return new File(jarDir(), jarKey(jar) + ".jar");
    }

    private File jarDir() {
        File dir = new File(App.getInstance().getCacheDir(), "jar");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File create(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (file.exists()) file.delete();
        file.createNewFile();
        file.setReadable(true);
        file.setWritable(true);
        file.setExecutable(true);
        return file;
    }

    private boolean exists(File file) {
        return file != null && file.exists() && file.length() > 0;
    }

    private Object lock(String key) {
        Object lock = locks.get(key);
        if (lock != null) return lock;
        Object created = new Object();
        Object old = locks.putIfAbsent(key, created);
        return old == null ? created : old;
    }

    private String jarKey(String jar) {
        String key = MD5.string2MD5(jar == null ? "" : jar);
        return TextUtils.isEmpty(key) ? MAIN_KEY : key;
    }

    private String realKey(String key) {
        String alias = aliases.get(key);
        return TextUtils.isEmpty(alias) ? key : alias;
    }

    private String className(String api) {
        return api.contains("csp_") ? api.split("csp_")[1] : api;
    }

    /** 将 clan:// 协议地址转换为可访问的 http 地址，支持 clan://localhost 与 clan://host 两种形式 */
    private String clanToAddress(String url) {
        if (url.startsWith("clan://localhost/")) {
            return url.replace("clan://localhost/", ControlManager.get().getAddress(true) + "file/");
        }
        if (url.startsWith("clan://")) {
            String text = url.substring(7);
            int index = text.indexOf('/');
            if (index > 0) return "http://" + text.substring(0, index) + "/file/" + text.substring(index + 1);
        }
        return url;
    }

    /** 同步本地服务端口到主进程与 jar 内的 com.github.catvod.Proxy 类 */
    private void injectProxyPort(DexClassLoader loader) {
        // 先同步主进程 Proxy 占位类，jar 内代码未自带 Proxy 时会回落到主进程访问
        com.github.catvod.Proxy.set(getServerPort());
        if (loader == null) return;
        try {
            Class<?> proxy = loader.loadClass("com.github.catvod.Proxy");
            Method set = proxy.getMethod("set", int.class);
            set.invoke(null, getServerPort());
        } catch (Throwable ignored) {
            // jar 内未实现 Proxy.set(int) 时属正常情况，忽略即可
        }
    }

    private int getServerPort() {
        try {
            String address = ControlManager.get().getAddress(true);
            if (address != null && address.startsWith("http://127.0.0.1:")) {
                String baseUrl = address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
                return Integer.parseInt(baseUrl.substring(baseUrl.lastIndexOf(":") + 1));
            }
        } catch (Throwable ignored) {
        }
        return RemoteServer.serverPort;
    }

    private void close(java.io.Closeable closeable) {
        try {
            if (closeable != null) closeable.close();
        } catch (Throwable ignored) {
        }
    }
}
