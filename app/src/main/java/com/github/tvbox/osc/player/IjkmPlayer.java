package com.github.tvbox.osc.player;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;
import xyz.doikki.videoplayer.ijk.IjkPlayer;
import xyz.doikki.videoplayer.ijk.RawDataSourceProvider;

public class IjkmPlayer extends IjkPlayer {

    private IJKCode codec = null;

    public IjkmPlayer(Context context, IJKCode codec) {
        super(context);
        this.codec = codec;
    }

    @Override
    public void setOptions() {
        // 根据缓冲模式应用不同配置
        // 0 = 默认内置, 1 = API配置, 2 = 流畅模式, 3 = 均衡模式, 4 = 原画模式
        int bufferMode = Hawk.get(HawkConfig.BUFFER_MODE, 3);
        
        LinkedHashMap<String, String> options = null;
        
        // 只有 API 配置模式才使用 API 提供的配置
        if (bufferMode == 1) {
            IJKCode codecTmp = this.codec == null ? ApiConfig.get().getCurrentIJKCode() : this.codec;
            options = codecTmp.getOption();
            if (options != null) {
                for (String key : options.keySet()) {
                    String value = options.get(key);
                    String[] opt = key.split("\\|");
                    int category = Integer.parseInt(opt[0].trim());
                    String name = opt[1].trim();
                    try {
                        long valLong = Long.parseLong(value);
                        mMediaPlayer.setOption(category, name, valLong);
                    } catch (Exception e) {
                        mMediaPlayer.setOption(category, name, value);
                    }
                }
            }
        }
        
        //开启内置字幕
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", -1);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,"safe",0);
        
        // 强制启用 soundtouch 支持变速播放（如果 API 配置中没有提供）
        if (options == null || !options.containsKey("4|soundtouch")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1);
        }
        
        switch (bufferMode) {
            case 4:
                // 原画模式 - 更大的缓冲，适合高码率视频
                applyOriginalMode(options);
                break;
            case 3:
                // 均衡模式 - 中等缓冲，平衡流畅度和画质
                applyBalancedMode(options);
                break;
            case 2:
                // 流畅模式 - 较小的缓冲，快速启动
                applySmoothMode(options);
                break;
            case 1:
                // API配置 - 使用API提供的配置，不应用额外优化
                break;
            case 0:
            default:
                // 默认内置 - 使用IJK默认配置，不应用额外优化
                break;
        }
        
        super.setOptions();
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        try {
            if (path != null && !TextUtils.isEmpty(path)) {
                if(path.startsWith("rtsp")){
                    mMediaPlayer.setOption(1, "infbuf", 1);
                    mMediaPlayer.setOption(1, "rtsp_transport", "tcp");
                    mMediaPlayer.setOption(1, "rtsp_flags", "prefer_tcp");
                } else if (!path.contains(".m3u8") && (path.contains(".mp4") || path.contains(".mkv") || path.contains(".avi"))) {
                    if (Hawk.get(HawkConfig.IJK_CACHE_PLAY, false)) {
                        String cachePath = FileUtils.getExternalCachePath() + "/ijkcaches/";
                        String cacheMapPath = cachePath;
                        File cacheFile = new File(cachePath);
                        if (!cacheFile.exists()) cacheFile.mkdirs();
                        String tmpMd5 = MD5.string2MD5(path);
                        cachePath += tmpMd5 + ".file";
                        cacheMapPath += tmpMd5 + ".map";
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_file_path", cachePath);
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_map_path", cacheMapPath);
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "parse_cache_map", 1);
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "auto_save_map", 1);
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_max_capacity", 60 * 1024 * 1024);
                        path = "ijkio:cache:ffio:" + path;
                    }
                }
            }
            setDataSourceHeader(headers);
        } catch (Exception e) {
            mPlayerEventListener.onError(-1, PlayerHelper.getRootCauseMessage(e));
        }
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "ijkio,ffio,async,cache,crypto,file,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data");
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "ijkio,ffio,async,cache,crypto,file,dash,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data,concat,subfile,ffconcat");
      
        super.setDataSource(path, headers);
    }

    private String encodeSpaceChinese(String str) throws UnsupportedEncodingException {
        Pattern p = Pattern.compile("[\u4e00-\u9fa5 ]+");
        Matcher m = p.matcher(str);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, URLEncoder.encode(m.group(0), "UTF-8"));
        m.appendTail(b);
        return b.toString();
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        try {
            mMediaPlayer.setDataSource(new RawDataSourceProvider(fd));
        } catch (Exception e) {
            mPlayerEventListener.onError(-1, PlayerHelper.getRootCauseMessage(e));
        }
    }
    /**
     * 流畅模式 - 适合普通视频，快速启动，适度缓冲
     */
    private void applySmoothMode(LinkedHashMap<String, String> options) {
        // 启用数据包缓冲
        if (options == null || !options.containsKey("4|packet-buffering")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
        }
        // 最大缓冲大小 50MB（与Exo流畅模式一致）
        if (options == null || !options.containsKey("1|max-buffer-size")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max-buffer-size", 50 * 1024 * 1024);
        }
        // 最小帧数，减少缓冲等待
        if (options == null || !options.containsKey("4|min-frames")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 5);
        }
        // 最大缓存时长15秒（与Exo流畅模式maxBufferMs一致）
        if (options == null || !options.containsKey("4|max_cached_duration")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 15000);
        }
        // 启用丢帧以保持同步
        if (options == null || !options.containsKey("4|framedrop")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        }
        // 分析时长，减少首播时间
        if (options == null || !options.containsKey("1|analyzeduration")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1 * 1000 * 1000);
        }
        // 探测大小
        if (options == null || !options.containsKey("1|probesize")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 16);
        }
        // 启用快速seek
        if (options == null || !options.containsKey("1|fflags")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek");
        }
        // 适中的缓冲等待
        if (options == null || !options.containsKey("4|max-buffer-time")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-time", 5000);
        }
    }

    /**
     * 均衡模式 - 较大缓冲，平衡流畅度和内存占用
     */
    private void applyBalancedMode(LinkedHashMap<String, String> options) {
        // 启用数据包缓冲
        if (options == null || !options.containsKey("4|packet-buffering")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
        }
        // 最大缓冲大小 128MB（较大缓冲，更流畅）
        if (options == null || !options.containsKey("1|max-buffer-size")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max-buffer-size", 128 * 1024 * 1024);
        }
        // 最小帧数适中
        if (options == null || !options.containsKey("4|min-frames")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 10);
        }
        // 最大缓存时长45秒（与Exo均衡模式maxBufferMs一致）
        if (options == null || !options.containsKey("4|max_cached_duration")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 45000);
        }
        // 均衡模式下少量丢帧
        if (options == null || !options.containsKey("4|framedrop")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        }
        // 分析时长适中
        if (options == null || !options.containsKey("1|analyzeduration")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2 * 1000 * 1000);
        }
        // 探测大小适中
        if (options == null || !options.containsKey("1|probesize")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 48);
        }
        // 启用快速seek
        if (options == null || !options.containsKey("1|fflags")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek");
        }
        // 较大的缓冲时间
        if (options == null || !options.containsKey("4|max-buffer-time")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-time", 8000);
        }
        // 适中的连接超时时间
        if (options == null || !options.containsKey("1|timeout")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 30 * 1000 * 1000);
        }
        // 重连次数
        if (options == null || !options.containsKey("4|reconnect")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "reconnect", 3);
        }
    }

    /**
     * 原画模式 - 最大缓冲，最流畅，适合高码率视频和大文件
     */
    private void applyOriginalMode(LinkedHashMap<String, String> options) {
        // 启用数据包缓冲
        if (options == null || !options.containsKey("4|packet-buffering")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
        }
        // 最大缓冲大小 512MB（最大缓冲，最流畅）
        if (options == null || !options.containsKey("1|max-buffer-size")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max-buffer-size", 512 * 1024 * 1024);
        }
        // 最小帧数增加，确保流畅度
        if (options == null || !options.containsKey("4|min-frames")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 15);
        }
        // 最大缓存时长120秒（与Exo原画模式maxBufferMs一致）
        if (options == null || !options.containsKey("4|max_cached_duration")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 120000);
        }
        // 原画模式下减少丢帧，保证画质
        if (options == null || !options.containsKey("4|framedrop")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 0);
        }
        // 分析时长增加到5秒（大文件需要更多时间解析）
        if (options == null || !options.containsKey("1|analyzeduration")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 5 * 1000 * 1000);
        }
        // 探测大小增加到128KB
        if (options == null || !options.containsKey("1|probesize")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 128);
        }
        // 启用快速seek
        if (options == null || !options.containsKey("1|fflags")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek");
        }
        // 增加缓冲时间
        if (options == null || !options.containsKey("4|max-buffer-time")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-time", 10000);
        }
        // 增加连接超时时间到60秒
        if (options == null || !options.containsKey("1|timeout")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 60 * 1000 * 1000);
        }
        // 增加重连次数
        if (options == null || !options.containsKey("4|reconnect")) {
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "reconnect", 3);
        }
    }

    private void setDataSourceHeader(Map<String, String> headers) {
        if (headers != null && !headers.isEmpty()) {
            String userAgent = headers.get("User-Agent");
            if (!TextUtils.isEmpty(userAgent)) {
                mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", userAgent);
                // 移除header中的User-Agent，防止重复
                headers.remove("User-Agent");
            }
            if (headers.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    sb.append(entry.getKey());
                    sb.append(":");
                    String value = entry.getValue();
                    if (!TextUtils.isEmpty(value))
                        sb.append(entry.getValue());
                    sb.append("\r\n");
                    mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", sb.toString());
                }
            }
        }
    }
    public TrackInfo getTrackInfo() {
        IjkTrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
        if (trackInfo == null) return null;
        TrackInfo data = new TrackInfo();
        int subtitleSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        int index = 0;
        for (IjkTrackInfo info : trackInfo) {
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {//音轨信息
                String trackName = (data.getAudio().size() + 1) + "：" + info.getInfoInline();
                TrackInfoBean t = new TrackInfoBean();
                t.name = trackName;
                t.language = info.getLanguage();
                t.trackId = index;
                t.selected = index == audioSelected;
                data.addAudio(t);
            }
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {//内置字幕
                String trackName = (data.getSubtitle().size() + 1) + "：" + info.getInfoInline();
                TrackInfoBean t = new TrackInfoBean();
                t.name = trackName;
                t.language = info.getLanguage();
                t.trackId = index;
                t.selected = index == subtitleSelected;
                data.addSubtitle(t);
            }
            index++;
        }
        return data;
    }

    public void setTrack(int trackIndex) {
        mMediaPlayer.selectTrack(trackIndex);
    }

    public void setOnTimedTextListener(IMediaPlayer.OnTimedTextListener listener) {
        mMediaPlayer.setOnTimedTextListener(listener);
    }

}
