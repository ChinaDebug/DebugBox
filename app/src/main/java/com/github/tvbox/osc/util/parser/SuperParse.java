package com.github.tvbox.osc.util.parser;

import android.util.Base64;

import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SuperParse {
    // 记录当前 flag 对应的 Web 嗅探解析地址，供 loadHtml 使用
    public static HashMap<String, ArrayList<String>> flagWebJx = new HashMap<>();
    // Web 嗅探解析地址与解析源名称的映射，供 shouldInterceptRequest 反查解析来源
    private static Map<String, String> webJxNameMap = new ConcurrentHashMap<>();
    // 根据 ext.flag 建立的映射缓存
    private static HashMap<String, ArrayList<String>> configs = null;
    // 当前批次可用的 JSON 解析接口集合
    private static LinkedHashMap<String, String> jsonJx = null;
    // 当前批次可用的 Web 嗅探解析地址集合
    private static ArrayList<String> webJx = null;

    /**
     * 清理所有静态缓存，在配置切换或页面销毁时调用
     */
    public static synchronized void clear() {
        if (configs != null) {
            configs.clear();
            configs = null;
        }
        if (jsonJx != null) {
            jsonJx.clear();
            jsonJx = null;
        }
        if (webJx != null) {
            webJx.clear();
            webJx = null;
        }
        flagWebJx.clear();
        webJxNameMap.clear();
    }

    /**
     * 彻底释放解析相关资源，包含 JsonParallel 的线程池
     */
    public static void release() {
        clear();
        JsonParallel.release();
    }

    public static JSONObject parse(LinkedHashMap<String, HashMap<String, String>> jx, String flag, String url) {
        try {
            // 每次超级解析前清空上一轮 flag 对应的嗅探地址，避免旧数据干扰
            if (flag != null) {
                flagWebJx.remove(flag);
            }
            // 每次超级解析前清空上一轮 Web 嗅探解析源映射
            webJxNameMap.clear();

            // 初始化全局配置（configs）一次
            if (configs == null) {
                configs = new HashMap<>();
                for (Map.Entry<String, HashMap<String, String>> entry : jx.entrySet()) {
                    String key = entry.getKey();
                    HashMap<String, String> parseBean = entry.getValue();
                    if (parseBean == null) {
                        continue;
                    }
                    String type = parseBean.get("type");
                    if (type == null) {
                        continue;
                    }
                    if ("1".equals(type) || "0".equals(type)) {
                        try {
                            String ext = parseBean.get("ext");
                            if (ext == null || ext.trim().isEmpty()) {
                                continue;
                            }
                            JSONObject extJson = new JSONObject(ext);
                            if (!extJson.has("flag")) {
                                continue;
                            }
                            JSONArray flagsArray = extJson.getJSONArray("flag");
                            for (int j = 0; j < flagsArray.length(); j++) {
                                String flagKey = flagsArray.getString(j).trim().toLowerCase();
                                ArrayList<String> flagJx = configs.get(flagKey);
                                if (flagJx == null) {
                                    flagJx = new ArrayList<>();
                                    configs.put(flagKey, flagJx);
                                }
                                if (!flagJx.contains(key)) {
                                    flagJx.add(key);
                                }
                            }
                        } catch (Exception e) {
                            SpiderDebug.log(e);
                        }
                    }
                }
            }

            // 根据配置构建 jsonJx 和 webJx
            jsonJx = new LinkedHashMap<>();
            webJx = new ArrayList<>();
            String normalizedFlag = flag == null ? "" : flag.trim().toLowerCase();
            List<String> targetKeys = new ArrayList<>();
            for (String flagKey : configs.keySet()) {
                String normalizedFlagKey = flagKey.trim().toLowerCase();
                if (normalizedFlag.contains(normalizedFlagKey)) {
                    targetKeys.addAll(configs.get(flagKey));
                }
            }
            if (!targetKeys.isEmpty()) {
                for (String key : targetKeys) {
                    addParseBeanToGroup(jx, key);
                }
            } else {
                // 没有匹配 flag 时 fallback 到全部解析，保证可用性
                for (Map.Entry<String, HashMap<String, String>> entry : jx.entrySet()) {
                    addParseBeanToGroup(jx, entry.getKey());
                }
            }

            if (!webJx.isEmpty()) {
                // flag 可能为 null，使用空字符串作为安全键，避免 HashMap put 空指针
                String safeFlag = flag == null ? "" : flag;
                flagWebJx.put(safeFlag, webJx);
            }

            if (!webJx.isEmpty()) {
                JSONObject webResult = new JSONObject();
                String safeFlag = flag == null ? "" : flag;
                webResult.put("url", "proxy://go=SuperParse&flag=" + safeFlag + "&url=" + Base64.encodeToString(url.getBytes(), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP));
                webResult.put("parse", 1);
                webResult.put("ua", Utils.UaWinChrome);
                return webResult;
            }
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return new JSONObject();
    }

    /**
     * 按名称从解析集合中提取并归类到 jsonJx / webJx
     */
    private static void addParseBeanToGroup(LinkedHashMap<String, HashMap<String, String>> jx, String key) {
        HashMap<String, String> parseBean = jx.get(key);
        if (parseBean == null) {
            return;
        }
        String type = parseBean.get("type");
        if (type == null) {
            return;
        }
        if ("1".equals(type)) {
            String urlValue = parseBean.get("url");
            String ext = parseBean.get("ext");
            if (urlValue != null && ext != null) {
                jsonJx.put(key, mixUrl(urlValue, ext));
            }
        } else if ("0".equals(type)) {
            String urlValue = parseBean.get("url");
            if (urlValue != null) {
                webJx.add(urlValue);
                // 记录解析源地址与名称的映射，用于 WebView 嗅探成功后反查来源
                webJxNameMap.put(urlValue, key);
            }
        }
    }

    /**
     * 根据请求 URL 或 Referer 反查 Web 嗅探解析源名称
     */
    public static String findJxNameByUrl(String url) {
        if (url == null || webJxNameMap.isEmpty()) {
            return null;
        }
        String inputHost = extractHost(url);
        for (Map.Entry<String, String> entry : webJxNameMap.entrySet()) {
            String jxUrl = entry.getKey();
            if (jxUrl == null) {
                continue;
            }
            // 优先精确匹配或前缀匹配，避免 contains 子串误匹配
            if (url.equalsIgnoreCase(jxUrl) || url.startsWith(jxUrl)) {
                return entry.getValue();
            }
            // Referer/Origin 通常只有 host，按 host 匹配解析源
            String jxHost = extractHost(jxUrl);
            if (jxHost != null && inputHost != null && jxHost.equalsIgnoreCase(inputHost)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 从 URL 中提取 host（含端口），忽略协议与前后空格
     */
    private static String extractHost(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        int start = u.indexOf("://");
        if (start == -1) {
            start = 0;
        } else {
            start += 3;
        }
        int end = u.indexOf("/", start);
        if (end == -1) {
            end = u.length();
        }
        if (start >= end) {
            return null;
        }
        return u.substring(start, end);
    }

    public static JSONObject doJsonJx(LinkedHashMap<String, String> json_jxs, String url) {
        return JsonParallel.parse(json_jxs, url);
    }

    public static JSONObject doJsonJx(String url) {
        return JsonParallel.parse(jsonJx, url);
    }

    public static void stopJsonJx() {
        JsonParallel.cancelTasks();
    }

    private static String mixUrl(String url, String ext) {
        if (ext != null && ext.trim().length() > 0) {
            int idx = url.indexOf("?");
            if (idx > 0) {
                return url.substring(0, idx + 1) + "cat_ext=" + Base64.encodeToString(ext.getBytes(), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP) + "&" + url.substring(idx + 1);
            }
        }
        return url;
    }

    public static Object[] loadHtml(String flag, String url) {
        try {
            url = new String(Base64.decode(url, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
            // JS 控制所有 iframe 错峰加载：先创建全部 iframe，再每隔 50ms 设置一个 src
            String html = "\n" +
                    "<!doctype html>\n" +
                    "<html>\n" +
                    "<head>\n" +
                    "<title>解析</title>\n" +
                    "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" />\n" +
                    "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=EmulateIE10\" />\n" +
                    "<meta name=\"renderer\" content=\"webkit|ie-comp|ie-stand\">\n" +
                    "<meta name=\"viewport\" content=\"width=device-width\">\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "<script>\n" +
                    "var apiArray=[#jxs#];\n" +
                    "var urlPs=\"#url#\";\n" +
                    "var iframes=[];\n" +
                    "var loadIndex=0;\n" +
                    "var loadTimer=null;\n" +
                    "for(var i=0;i<apiArray.length;i++){\n" +
                    "var f=document.createElement('iframe');\n" +
                    "f.setAttribute('sandbox','allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox allow-top-navigation allow-pointer-lock');\n" +
                    "f.setAttribute('frameborder','0');\n" +
                    "f.setAttribute('allowfullscreen','true');\n" +
                    "f.setAttribute('webkitallowfullscreen','true');\n" +
                    "f.setAttribute('mozallowfullscreen','true');\n" +
                    "f.style.width='100%';\n" +
                    "f.style.height='100%';\n" +
                    "f.style.border='none';\n" +
                    "document.body.appendChild(f);\n" +
                    "iframes.push(f);\n" +
                    "}\n" +
                    "function loadNextIframe(){\n" +
                    "if(window.stopSuperParse){\n" +
                    "clearInterval(loadTimer);\n" +
                    "loadTimer=null;\n" +
                    "for(var j=0;j<iframes.length;j++){\n" +
                    "try{iframes[j].src='about:blank';}catch(e){}\n" +
                    "}\n" +
                    "return;\n" +
                    "}\n" +
                    "if(loadIndex>=iframes.length){\n" +
                    "clearInterval(loadTimer);\n" +
                    "loadTimer=null;\n" +
                    "return;\n" +
                    "}\n" +
                    "iframes[loadIndex].src=apiArray[loadIndex]+urlPs;\n" +
                    "loadIndex++;\n" +
                    "}\n" +
                    "loadTimer=setInterval(loadNextIframe,50);\n" +
                    "loadNextIframe();\n" +
                    "if(typeof SuperParseAndroid!==\'undefined\'&&SuperParseAndroid.onPageReady){\n" +
                    "try{SuperParseAndroid.onPageReady();}catch(e){}\n" +
                    "}\n" +
                    "</script>\n" +
                    "</body>\n" +
                    "</html>";

            StringBuilder jxs = new StringBuilder();
            if (flagWebJx.containsKey(flag)) {
                ArrayList<String> jxUrls = flagWebJx.get(flag);
                for (int i = 0; i < jxUrls.size(); i++) {
                    jxs.append("\"");
                    jxs.append(jxUrls.get(i));
                    jxs.append("\"");
                    if (i < jxUrls.size() - 1) {
                        jxs.append(",");
                    }
                }
            }
            html = html.replace("#url#", url).replace("#jxs#", jxs.toString());
            Object[] result = new Object[3];
            result[0] = 200;
            result[1] = "text/html; charset=\"UTF-8\"";
            ByteArrayInputStream baos = new ByteArrayInputStream(html.getBytes("UTF-8"));
            result[2] = baos;
            return result;
        } catch (Throwable th) {
            SpiderDebug.log(th);
        }
        return null;
    }
}
