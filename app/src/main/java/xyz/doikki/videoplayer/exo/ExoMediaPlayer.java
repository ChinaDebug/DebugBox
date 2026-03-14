package xyz.doikki.videoplayer.exo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.TrafficStats;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelectionArray;
import androidx.media3.ui.PlayerView;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HawkUtils;
import com.github.tvbox.osc.util.PlayerHelper;
import com.orhanobut.hawk.Hawk;

import java.util.Locale;
import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.util.PlayerUtils;

public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

    protected Context mAppContext;
    protected ExoPlayer mMediaPlayer;
    protected MediaSource mMediaSource;
    protected ExoMediaSourceHelper mMediaSourceHelper;
    protected ExoTrackNameProvider trackNameProvider;
    protected TrackSelectionArray mTrackSelections;
    private PlaybackParameters mSpeedPlaybackParameters;
    private boolean mIsPreparing;

    private DefaultRenderersFactory mRenderersFactory;
    private DefaultTrackSelector mTrackSelector;

    private int errorCode = -1;
    private String path;
    private Map<String, String> headers;
    private long lastTotalRxBytes = 0;
    private long lastTimeStamp = 0;

    private long speedLastTotalRxBytes = -1;
    private long speedLastTimeStamp = -1;

    private int retriedTimes = 0;
    private boolean hasTunnelingFallback = false;

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
    }

    @SuppressLint("UnsafeOptInUsageError")
    @Override
    public void initPlayer() {
        // 每次都重新创建 RenderersFactory 以支持实时切换渲染器设置
        mRenderersFactory = HawkUtils.createExoRendererActualValue(mAppContext);
        //https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md
        mRenderersFactory.setExtensionRendererMode(HawkUtils.getExoRendererModeActualValue());

        // 每次都重新创建 TrackSelector 以支持实时切换
        mTrackSelector = new DefaultTrackSelector(mAppContext);
        // 每次都重新初始化 LoadControl 以支持实时切换缓冲模式
        // 根据缓冲模式应用不同配置
        // 0 = 默认内置, 1 = 流畅模式, 2 = 均衡模式, 3 = 原画模式
        int bufferMode = Hawk.get(HawkConfig.BUFFER_MODE_EXO, 2);
        DefaultLoadControl.Builder builder = new DefaultLoadControl.Builder();
        LoadControl mLoadControl;
        switch (bufferMode) {
            case 3:
                // 原画模式 - 最大缓冲，最流畅，适合高码率视频和大文件
                builder.setBufferDurationsMs(
                        30000,  // minBufferMs - 30秒（保持播放的最小缓冲）
                        120000, // maxBufferMs - 120秒（最大预加载，最流畅）
                        5000,   // bufferForPlaybackMs - 5秒开始播放
                        10000   // bufferForPlaybackAfterRebufferMs - 10秒恢复播放
                );
                // 设置目标缓冲字节数（约500MB）
                builder.setTargetBufferBytes(512 * 1024 * 1024);
                mLoadControl = builder.build();
                break;
            case 2:
                // 均衡模式 - 中等缓冲，平衡流畅度和内存
                builder.setBufferDurationsMs(
                        15000,  // minBufferMs - 15秒
                        45000,  // maxBufferMs - 45秒（中等预加载）
                        3000,   // bufferForPlaybackMs - 3秒开始播放
                        6000    // bufferForPlaybackAfterRebufferMs - 6秒恢复播放
                );
                // 设置目标缓冲字节数（约200MB）
                builder.setTargetBufferBytes(200 * 1024 * 1024);
                mLoadControl = builder.build();
                break;
            case 1:
                // 流畅模式 - 小缓冲，快速启动，省内存
                builder.setBufferDurationsMs(
                        5000,   // minBufferMs - 5秒
                        15000,  // maxBufferMs - 15秒（小预加载）
                        1500,   // bufferForPlaybackMs - 1.5秒，快速启动
                        3000    // bufferForPlaybackAfterRebufferMs - 3秒恢复播放
                );
                // 设置目标缓冲字节数（约50MB）
                builder.setTargetBufferBytes(50 * 1024 * 1024);
                mLoadControl = builder.build();
                break;
            case 0:
            default:
                // 默认内置 - 使用ExoPlayer默认配置，不自定义
                mLoadControl = new DefaultLoadControl();
                break;
        }
        mTrackSelector.setParameters(mTrackSelector.getParameters().buildUpon().setPreferredTextLanguage(Locale.getDefault().getISO3Language()).setTunnelingEnabled(true));
        /*mMediaPlayer = new ExoPlayer.Builder(
                mAppContext,
                mRenderersFactory,
                mTrackSelector,
                new DefaultMediaSourceFactory(mAppContext),
                mLoadControl,
                DefaultBandwidthMeter.getSingletonInstance(mAppContext),
                new AnalyticsCollector(Clock.DEFAULT))
                .build();*/
        mMediaPlayer = new ExoPlayer.Builder(mAppContext)
                .setLoadControl(mLoadControl)
                .setRenderersFactory(mRenderersFactory)
                .setTrackSelector(mTrackSelector).build();

        setOptions();

        mMediaPlayer.addListener(this);
        mMediaSourceHelper.clearSocksProxy();
    }

    public DefaultTrackSelector getTrackSelector() {
        return mTrackSelector;
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        this.path = path;
        this.headers = headers;
        mMediaSource = mMediaSourceHelper.getMediaSource(path, headers);
        errorCode = -1;
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        //no support
    }

    @Override
    public void start() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.stop();
    }

    @SuppressLint("UnsafeOptInUsageError")
    @Override
    public void prepareAsync() {
        if (mMediaPlayer == null)
            return;
        if (mMediaSource == null) return;
        if (mSpeedPlaybackParameters != null) {
            mMediaPlayer.setPlaybackParameters(mSpeedPlaybackParameters);
        }
        mIsPreparing = true;
        mMediaPlayer.setMediaSource(mMediaSource);
        mMediaPlayer.prepare();
    }

    @Override
    public void reset() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.clearMediaItems();
            mMediaPlayer.setVideoSurface(null);
            mIsPreparing = false;
        }
        hasTunnelingFallback = false;
        retriedTimes = 0;
        mTrackSelector.setParameters(mTrackSelector.getParameters().buildUpon().setTunnelingEnabled(true).build());
    }

    @Override
    public boolean isPlaying() {
        if (mMediaPlayer == null)
            return false;
        int state = mMediaPlayer.getPlaybackState();
        switch (state) {
            case Player.STATE_BUFFERING:
            case Player.STATE_READY:
                return mMediaPlayer.getPlayWhenReady();
            case Player.STATE_IDLE:
            case Player.STATE_ENDED:
            default:
                return false;
        }
    }

    @Override
    public void seekTo(long time) {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.seekTo(time);
    }

    @Override
    public void release() {
        if (mMediaPlayer != null) {
            mMediaPlayer.removeListener(this);
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
        mIsPreparing = false;
        mSpeedPlaybackParameters = null;
    }

    @Override
    public long getCurrentPosition() {
        if (mMediaPlayer == null)
            return 0;
        return mMediaPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        if (mMediaPlayer == null)
            return 0;
        return mMediaPlayer.getDuration();
    }

    @Override
    public boolean isLive() {
        if (mMediaPlayer == null)
            return false;
        return mMediaPlayer.isCurrentMediaItemLive();
    }

    @Override
    public int getBufferedPercentage() {
        return mMediaPlayer == null ? 0 : mMediaPlayer.getBufferedPercentage();
    }

    public void setPlayerView(PlayerView view) {
        if (mMediaPlayer != null) {
            view.setPlayer(mMediaPlayer);
        }
    }

    @Override
    public void setSurface(Surface surface) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setVideoSurface(surface);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (holder == null)
            setSurface(null);
        else
            setSurface(holder.getSurface());
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (mMediaPlayer != null)
            mMediaPlayer.setVolume((leftVolume + rightVolume) / 2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mMediaPlayer != null)
            mMediaPlayer.setRepeatMode(isLooping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
    }

    @Override
    public void setOptions() {
        //准备好就开始播放
        mMediaPlayer.setPlayWhenReady(true);
    }

    @Override
    public float getSpeed() {
        if (mSpeedPlaybackParameters != null) {
            return mSpeedPlaybackParameters.speed;
        }
        return 1f;
    }

    @Override
    public void setSpeed(float speed) {
        PlaybackParameters playbackParameters = new PlaybackParameters(speed);
        mSpeedPlaybackParameters = playbackParameters;
        if (mMediaPlayer != null) {
            mMediaPlayer.setPlaybackParameters(playbackParameters);
        }
    }

    private boolean unsupported() {
        return TrafficStats.getUidRxBytes(App.getInstance().getApplicationInfo().uid) == TrafficStats.UNSUPPORTED;
    }

    @Override
    public long getTcpSpeed() {
        if (mAppContext == null) {
            return 0;
        }
        long uidRxBytes = TrafficStats.getUidRxBytes(mAppContext.getApplicationInfo().uid);
        long nowTotalRxBytes = (uidRxBytes == TrafficStats.UNSUPPORTED) ? TrafficStats.getTotalRxBytes() : uidRxBytes;
        long nowTimeStamp = System.currentTimeMillis();
        if (speedLastTimeStamp <= 0) {
            speedLastTimeStamp = nowTimeStamp;
            speedLastTotalRxBytes = nowTotalRxBytes;
            return 0;
        }
        long calculationTime = nowTimeStamp - speedLastTimeStamp;
        if (calculationTime <= 0) {
            return 0;
        }
        long speed = ((nowTotalRxBytes - speedLastTotalRxBytes) * 1000 / calculationTime);
        speedLastTimeStamp = nowTimeStamp;
        speedLastTotalRxBytes = nowTotalRxBytes;
        return speed;
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        if (trackNameProvider == null)
            trackNameProvider = new ExoTrackNameProvider(mAppContext.getResources());
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (mPlayerEventListener == null) return;
        if (mIsPreparing) {
            if (playbackState == Player.STATE_READY) {
                mPlayerEventListener.onPrepared();
                mPlayerEventListener.onInfo(MEDIA_INFO_RENDERING_START, 0);
                mIsPreparing = false;
            }
            return;
        }
        switch (playbackState) {
            case Player.STATE_BUFFERING:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, getBufferedPercentage());
                break;
            case Player.STATE_READY:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, getBufferedPercentage());
                break;
            case Player.STATE_ENDED:
                mPlayerEventListener.onCompletion();
                break;
            case Player.STATE_IDLE:
                break;
        }
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        errorCode = error.errorCode;
        Log.e("tag--", "" + error.errorCode);
        
        if (!hasTunnelingFallback) {
            hasTunnelingFallback = true;
            mTrackSelector.setParameters(mTrackSelector.getParameters().buildUpon().setTunnelingEnabled(false).build());
            retriedTimes = 0;
            mMediaPlayer.stop();
            mMediaPlayer.clearMediaItems();
            mMediaPlayer.setVideoSurface(null);
            mIsPreparing = false;
            setDataSource(path, headers);
            prepareAsync();
            return;
        }
        
        String proxyServer = Hawk.get(HawkConfig.PROXY_SERVER, "");
        if ("".equals(proxyServer)) {
            if (retriedTimes == 0) {
                retriedTimes = 1;
                mMediaPlayer.stop();
                mMediaPlayer.clearMediaItems();
                mMediaPlayer.setVideoSurface(null);
                mIsPreparing = false;
                setDataSource(path, headers);
                prepareAsync();
            } else {
                if (mPlayerEventListener != null) {
                    mPlayerEventListener.onError(error.errorCode, PlayerHelper.getRootCauseMessage(error));
                }
            }
            return;
        }
        String[] proxyServers = proxyServer.split("\\s+|,|;|，");
        if (retriedTimes > proxyServers.length - 1) {
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError(error.errorCode, PlayerHelper.getRootCauseMessage(error));
            }
            return;
        }
        String[] ps = proxyServers[retriedTimes].split(":");
        if (ps.length != 2) {
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError(error.errorCode, PlayerHelper.getRootCauseMessage(error));
            }
            return;
        }
        try {
            mMediaSourceHelper.setSocksProxy(ps[0], Integer.parseInt(ps[1]));
            retriedTimes++;
            mMediaPlayer.stop();
            mMediaPlayer.clearMediaItems();
            mMediaPlayer.setVideoSurface(null);
            mIsPreparing = false;
            setDataSource(path, headers);
            prepareAsync();
        } catch (Exception e) {
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError(error.errorCode, PlayerHelper.getRootCauseMessage(error));
            }
            return;
        }
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onVideoSizeChanged(videoSize.width, videoSize.height);
            if (videoSize.unappliedRotationDegrees > 0) {
                mPlayerEventListener.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, videoSize.unappliedRotationDegrees);
            }
        }
    }
}
