package com.github.tvbox.osc.util;

import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public class AdBlocker {
    private static final Set<String> AD_HOSTS_EXACT = new HashSet<>();
    private static final Set<String> AD_HOSTS_WILDCARD = new HashSet<>();

    public static void clear() {
        AD_HOSTS_EXACT.clear();
        AD_HOSTS_WILDCARD.clear();
    }

    public static boolean isEmpty() {
        return AD_HOSTS_EXACT.isEmpty() && AD_HOSTS_WILDCARD.isEmpty();
    }

    public static void addAdHost(String host) {
        if (host == null || host.isEmpty()) {
            return;
        }
        host = host.toLowerCase().trim();
        // 去掉协议头
        if (host.startsWith("http://")) {
            host = host.substring(7);
        } else if (host.startsWith("https://")) {
            host = host.substring(8);
        }
        // 去掉路径，只保留 host:port 部分
        int pathIndex = host.indexOf('/');
        if (pathIndex >= 0) {
            host = host.substring(0, pathIndex);
        }
        // 去掉末尾的 .
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            return;
        }
        // 通配符 *.example.com 或 .example.com 统一处理成 .example.com
        if (host.startsWith("*.")) {
            AD_HOSTS_WILDCARD.add(host.substring(1));
        } else if (host.startsWith(".")) {
            AD_HOSTS_WILDCARD.add(host);
        } else {
            AD_HOSTS_EXACT.add(host);
            // 同时把 .example.com 加入通配，用于拦截其子域名
            AD_HOSTS_WILDCARD.add("." + host);
        }
    }

    public static boolean hasHost(String host) {
        if (host == null) {
            return false;
        }
        String key = host.toLowerCase().trim();
        return AD_HOSTS_EXACT.contains(key) || AD_HOSTS_WILDCARD.contains(key);
    }

    public static boolean isAd(String url) {
        if (url == null) {
            return false;
        }
        String host;
        try {
            URL u = new URL(url);
            host = u.getHost();
        } catch (MalformedURLException e) {
            // 非标准 URL，降级做简单包含匹配
            String lowerUrl = url.toLowerCase();
            for (String adHost : AD_HOSTS_EXACT) {
                if (lowerUrl.contains(adHost)) {
                    return true;
                }
            }
            return false;
        }
        if (host == null || host.isEmpty()) {
            return false;
        }
        host = host.toLowerCase();
        if (AD_HOSTS_EXACT.contains(host)) {
            return true;
        }
        // 通配匹配：a.b.example.com 可以匹配 .example.com 和 .b.example.com
        for (int i = 0; i < host.length(); i++) {
            if (host.charAt(i) == '.' && AD_HOSTS_WILDCARD.contains(host.substring(i))) {
                return true;
            }
        }
        return false;
    }

    public static WebResourceResponse createEmptyResource() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
    }
}