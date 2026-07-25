package com.github.catvod;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.server.RemoteServer;

/**
 * 主进程代理端口占位类。
 * jar 内爬虫通过此类获取本地服务端口与代理 URL，
 * 在 ControlManager 启动服务与 JarLoader 加载 jar 时同步端口，
 * 避免端口被占用后变更时 jar 内仍持有旧端口导致请求失败。
 */
public class Proxy {

    private static int port = RemoteServer.serverPort;

    public static void set(int port) {
        Proxy.port = port;
    }

    public static int getPort() {
        return port > 0 ? port : RemoteServer.serverPort;
    }

    /** 获取代理 URL，local=true 返回 127.0.0.1 地址便于 jar 内自访问 */
    public static String getUrl(boolean local) {
        return "http://" + (local ? "127.0.0.1" : getIp()) + ":" + getPort() + "/proxy";
    }

    private static String getIp() {
        try {
            return RemoteServer.getLocalIPAddress(App.getInstance());
        } catch (Throwable th) {
            return "127.0.0.1";
        }
    }
}
