package com.github.tvbox.osc.util;

/**
 * URL 相关工具方法
 */
public class UrlUtils {

    /**
     * 判断是否为可直接播放的媒体地址（http/https/rtmp/rtsp/rtp 协议且后缀为常见媒体格式）
     */
    public static boolean isDirectPlayUrl(String url) {
        if (StringUtils.isEmpty(url)) {
            return false;
        }
        String lowerUrl = url.toLowerCase();
        boolean isDirect = lowerUrl.startsWith("http://")
                || lowerUrl.startsWith("https://")
                || lowerUrl.startsWith("rtmp://")
                || lowerUrl.startsWith("rtsp://")
                || lowerUrl.startsWith("rtp://");
        if (isDirect) {
            isDirect = lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".flv")
                    || lowerUrl.endsWith(".mkv") || lowerUrl.endsWith(".ts") || lowerUrl.endsWith(".avi")
                    || lowerUrl.endsWith(".wmv") || lowerUrl.endsWith(".webm") || lowerUrl.endsWith(".mov")
                    || lowerUrl.endsWith(".rmvb") || lowerUrl.endsWith(".3gp") || lowerUrl.contains(".m3u8?")
                    || lowerUrl.contains(".mp4?") || lowerUrl.contains(".flv?") || lowerUrl.contains(".ts?");
        }
        return isDirect;
    }
}
