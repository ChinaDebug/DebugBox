package com.github.tvbox.osc.cast;

import android.content.Context;

import com.github.tvbox.osc.cast.model.CastData;
import com.github.tvbox.osc.cast.model.CastDevice;
import com.github.tvbox.osc.cast.discovery.DeviceDiscovery;

import java.util.List;

/**
 * Java 桥接类
 * 为现有 Java 代码提供投屏功能的访问接口
 */
public class CastBridge {

    private static CastBridge instance;
    private final CastManager castManager;

    private CastBridge(Context context) {
        castManager = CastManager.getInstance(context);
    }

    public static synchronized CastBridge getInstance(Context context) {
        if (instance == null) {
            instance = new CastBridge(context);
        }
        return instance;
    }

    /**
     * 启动投屏服务（接收端）
     */
    public boolean startCastServer() {
        return castManager.startCastServer();
    }

    /**
     * 停止投屏服务
     */
    public void stopCastServer() {
        castManager.stopCastServer();
    }

    /**
     * 开始搜索设备
     */
    public void startDeviceDiscovery(final DeviceDiscoveryCallback callback) {
        castManager.startDeviceDiscovery(new DeviceDiscovery.DeviceDiscoveryListener() {
            @Override
            public void onDeviceFound(CastDevice device) {
                if (callback != null) {
                    callback.onDeviceFound(device);
                }
            }

            @Override
            public void onDiscoveryComplete() {
                if (callback != null) {
                    callback.onDiscoveryComplete();
                }
            }

            @Override
            public void onDiscoveryError(String error) {
                if (callback != null) {
                    callback.onDiscoveryError(error);
                }
            }
        });
    }

    /**
     * 停止搜索设备
     */
    public void stopDeviceDiscovery() {
        castManager.stopDeviceDiscovery();
    }

    /**
     * 获取已发现的设备列表
     */
    public List<CastDevice> getDiscoveredDevices() {
        return castManager.getDiscoveredDevices();
    }

    /**
     * 投屏到指定设备
     */
    public void castToDevice(CastDevice device, CastData data, final CastCallback callback) {
        castManager.castToDevice(device, data, success -> {
            if (callback != null) {
                callback.onResult(success);
            }
            return null;
        });
    }

    /**
     * 暂停播放
     */
    public void pauseCast(final CastCallback callback) {
        castManager.controlPlay(CastManager.ControlAction.Pause.INSTANCE, success -> {
            if (callback != null) {
                callback.onResult(success);
            }
            return null;
        });
    }

    /**
     * 继续播放
     */
    public void resumeCast(final CastCallback callback) {
        castManager.controlPlay(CastManager.ControlAction.Resume.INSTANCE, success -> {
            if (callback != null) {
                callback.onResult(success);
            }
            return null;
        });
    }

    /**
     * 停止播放
     */
    public void stopCast(final CastCallback callback) {
        castManager.controlPlay(CastManager.ControlAction.Stop.INSTANCE, success -> {
            if (callback != null) {
                callback.onResult(success);
            }
            return null;
        });
    }

    /**
     * 跳转播放位置
     */
    public void seekCast(long position, final CastCallback callback) {
        castManager.controlPlay(new CastManager.ControlAction.Seek(position), success -> {
            if (callback != null) {
                callback.onResult(success);
            }
            return null;
        });
    }

    /**
     * 断开投屏连接
     */
    public void disconnect() {
        castManager.disconnect();
    }

    /**
     * 设置投屏监听器
     */
    public void setCastListener(final CastListener listener) {
        castManager.setCastListener(new CastManager.CastListener() {
            @Override
            public void onReceivePlay(CastData data) {
                try {
                    if (listener != null) {
                        listener.onReceivePlay(data);
                    }
                } catch (Exception e) {
                    android.util.Log.e("CastBridge", "onReceivePlay error: " + e.getMessage(), e);
                }
            }

            @Override
            public void onReceivePause() {
                try {
                    if (listener != null) {
                        listener.onReceivePause();
                    }
                } catch (Exception e) {
                    android.util.Log.e("CastBridge", "onReceivePause error: " + e.getMessage(), e);
                }
            }

            @Override
            public void onReceiveResume() {
                try {
                    if (listener != null) {
                        listener.onReceiveResume();
                    }
                } catch (Exception e) {
                    android.util.Log.e("CastBridge", "onReceiveResume error: " + e.getMessage(), e);
                }
            }

            @Override
            public void onReceiveStop() {
                try {
                    if (listener != null) {
                        listener.onReceiveStop();
                    }
                } catch (Exception e) {
                    android.util.Log.e("CastBridge", "onReceiveStop error: " + e.getMessage(), e);
                }
            }

            @Override
            public void onReceiveSeek(long position) {
                try {
                    if (listener != null) {
                        listener.onReceiveSeek(position);
                    }
                } catch (Exception e) {
                    android.util.Log.e("CastBridge", "onReceiveSeek error: " + e.getMessage(), e);
                }
            }

            @Override
            public CastData onStatusRequested() {
                try {
                    if (listener != null) {
                        return listener.onStatusRequested();
                    }
                } catch (Exception e) {
                    android.util.Log.e("CastBridge", "onStatusRequested error: " + e.getMessage(), e);
                }
                return new CastData();
            }
        });
    }

    /**
     * 释放资源
     */
    public static void release() {
        CastManager.releaseInstance();
        instance = null;
    }

    /**
     * 设备发现回调接口
     */
    public interface DeviceDiscoveryCallback {
        void onDeviceFound(CastDevice device);
        void onDiscoveryComplete();
        void onDiscoveryError(String error);
    }

    /**
     * 投屏操作回调接口
     */
    public interface CastCallback {
        void onResult(boolean success);
    }

    /**
     * 投屏接收监听器接口
     */
    public interface CastListener {
        void onReceivePlay(CastData data);
        void onReceivePause();
        void onReceiveResume();
        void onReceiveStop();
        void onReceiveSeek(long position);
        CastData onStatusRequested();
    }
}
