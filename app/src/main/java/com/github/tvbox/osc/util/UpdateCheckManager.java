package com.github.tvbox.osc.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;
import org.greenrobot.eventbus.EventBus;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class UpdateCheckManager {

    public static final String UPDATE_CHECK_ENABLE = "update_check_enable";
    public static final String UPDATE_CHECK_WIFI_ONLY = "update_check_wifi_only";
    public static final String UPDATE_CHECK_LAST_TIME = "update_check_last_time";
    public static final String UPDATE_CHECK_STARTUP = "update_check_startup";
    public static final String UPDATE_CHECK_INTERVAL = "update_check_interval";

    public static final int INTERVAL_OFF = 0;
    public static final int INTERVAL_30MIN = 30;
    public static final int INTERVAL_1HOUR = 60;
    public static final int INTERVAL_2HOURS = 120;

    private static final int MAX_QUEUE_SIZE = 100;
    private static final int CHECK_TIMEOUT_SECONDS = 5;

    private static volatile UpdateCheckManager instance;
    private final ExecutorService executor;
    private final ThreadPoolExecutor checkExecutor;
    private final AtomicBoolean isChecking = new AtomicBoolean(false);
    private final Map<String, Boolean> updateCache = new HashMap<>();
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final AtomicInteger updateCount = new AtomicInteger(0);
    private final List<UpdateCheckListener> listeners = java.util.Collections.synchronizedList(new ArrayList<>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private volatile boolean hasUpdate = false;
    private volatile boolean isScheduledRunning = false;
    private WeakReference<Context> appContextRef;

    public interface UpdateCheckListener {
        void onCheckComplete(boolean hasUpdate, Map<String, Boolean> updates);
        void onCheckProgress(int current, int total);
        void onCheckError(String errorMessage);
    }

    private UpdateCheckManager() {
        executor = Executors.newSingleThreadExecutor();
        checkExecutor = new ThreadPoolExecutor(
            3, 5, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUE_SIZE),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public static UpdateCheckManager get() {
        if (instance == null) {
            synchronized (UpdateCheckManager.class) {
                if (instance == null) {
                    instance = new UpdateCheckManager();
                }
            }
        }
        return instance;
    }

    public boolean isEnable() {
        return Hawk.get(UPDATE_CHECK_ENABLE, true);
    }

    public void setEnable(boolean enable) {
        Hawk.put(UPDATE_CHECK_ENABLE, enable);
        if (!enable) {
            stopScheduledCheck();
            clearCache();
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
        } else {
            Context context = appContextRef != null ? appContextRef.get() : null;
            if (context != null && getCheckInterval() != INTERVAL_OFF) {
                startScheduledCheck(context);
            }
        }
    }

    public boolean isStartupCheck() {
        return Hawk.get(UPDATE_CHECK_STARTUP, true);
    }

    public void setStartupCheck(boolean startup) {
        Hawk.put(UPDATE_CHECK_STARTUP, startup);
    }

    public int getCheckInterval() {
        return Hawk.get(UPDATE_CHECK_INTERVAL, INTERVAL_OFF);
    }

    public void setCheckInterval(int intervalMinutes) {
        int oldInterval = getCheckInterval();
        Hawk.put(UPDATE_CHECK_INTERVAL, intervalMinutes);
        if (intervalMinutes == INTERVAL_OFF) {
            stopScheduledCheck();
        } else if (oldInterval == INTERVAL_OFF && isEnable()) {
            Context context = appContextRef != null ? appContextRef.get() : null;
            if (context != null) {
                startScheduledCheck(context);
            }
        }
    }

    public String getIntervalDisplay() {
        int interval = getCheckInterval();
        if (interval == INTERVAL_OFF) {
            return "关闭";
        } else if (interval == INTERVAL_30MIN) {
            return "30分钟";
        } else if (interval == INTERVAL_1HOUR) {
            return "1小时";
        } else if (interval == INTERVAL_2HOURS) {
            return "2小时";
        }
        return "关闭";
    }

    public boolean isWifiOnly() {
        return Hawk.get(UPDATE_CHECK_WIFI_ONLY, false);
    }

    public void setWifiOnly(boolean wifiOnly) {
        Hawk.put(UPDATE_CHECK_WIFI_ONLY, wifiOnly);
    }

    public boolean hasUpdate() {
        return hasUpdate;
    }

    public boolean isChecking() {
        return isChecking.get();
    }

    public boolean hasVideoUpdate(String sourceKey, String vodId) {
        String key = sourceKey + "_" + vodId;
        cacheLock.readLock().lock();
        try {
            Boolean update = updateCache.get(key);
            return update != null && update;
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    public void clearVideoUpdate(String sourceKey, String vodId) {
        String key = sourceKey + "_" + vodId;
        cacheLock.writeLock().lock();
        try {
            Boolean removed = updateCache.remove(key);
            if (removed != null && removed) {
                int count = updateCount.decrementAndGet();
                if (count <= 0) {
                    updateCount.set(0);
                    hasUpdate = false;
                }
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    public void setVideoUpdate(String sourceKey, String vodId, boolean hasUpdate) {
        String key = sourceKey + "_" + vodId;
        cacheLock.writeLock().lock();
        try {
            Boolean oldValue = updateCache.put(key, hasUpdate);
            if (hasUpdate && (oldValue == null || !oldValue)) {
                updateCount.incrementAndGet();
                this.hasUpdate = true;
            } else if (!hasUpdate && oldValue != null && oldValue) {
                int count = updateCount.decrementAndGet();
                if (count <= 0) {
                    updateCount.set(0);
                    this.hasUpdate = false;
                }
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    public void addListener(UpdateCheckListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(UpdateCheckListener listener) {
        listeners.remove(listener);
    }

    private long getMinCheckIntervalMs() {
        int interval = getCheckInterval();
        if (interval == INTERVAL_OFF) {
            return Long.MAX_VALUE;
        }
        return interval * 60 * 1000L;
    }

    public boolean canCheck(Context context) {
        if (!isEnable()) {
            return false;
        }

        if (isWifiOnly() && !isWifiConnected(context)) {
            return false;
        }

        return true;
    }

    public boolean shouldCheck(Context context) {
        if (!canCheck(context)) {
            return false;
        }

        long lastCheckTime = Hawk.get(UPDATE_CHECK_LAST_TIME, 0L);
        long now = System.currentTimeMillis();
        long minInterval = getMinCheckIntervalMs();

        return now - lastCheckTime >= minInterval;
    }

    public void startCheck(Context context) {
        startCheck(context, false, false);
    }

    public void startCheck(Context context, boolean force) {
        startCheck(context, force, false);
    }

    public boolean startManualCheck(Context context) {
        return startCheck(context, true, true);
    }

    public boolean startCheck(Context context, boolean force, boolean highConcurrency) {
        if (context != null && context.getApplicationContext() != null) {
            appContextRef = new WeakReference<>(context.getApplicationContext());
        }

        if (!force && !shouldCheck(context)) {
            return false;
        }

        if (!isChecking.compareAndSet(false, true)) {
            return false;
        }

        if (highConcurrency) {
            checkExecutor.setCorePoolSize(5);
        } else {
            checkExecutor.setCorePoolSize(3);
        }

        executor.execute(() -> {
            try {
                doCheck();
            } catch (Exception e) {
                LOG.e(e);
                notifyError("检测更新失败: " + e.getMessage());
            } finally {
                isChecking.set(false);
            }
        });
        return true;
    }

    public void startScheduledCheck(Context context) {
        if (isScheduledRunning) {
            return;
        }
        if (context != null && context.getApplicationContext() != null) {
            appContextRef = new WeakReference<>(context.getApplicationContext());
        }
        isScheduledRunning = true;
        scheduleNextCheck();
    }

    private void scheduleNextCheck() {
        if (!isScheduledRunning) {
            return;
        }

        Context context = appContextRef != null ? appContextRef.get() : null;
        int interval = getCheckInterval();
        if (interval == INTERVAL_OFF || !canCheck(context)) {
            mainHandler.postDelayed(this::scheduleNextCheck, 60 * 1000L);
            return;
        }

        long lastCheckTime = Hawk.get(UPDATE_CHECK_LAST_TIME, 0L);
        long now = System.currentTimeMillis();
        long intervalMs = interval * 60 * 1000L;
        long elapsed = now - lastCheckTime;

        long delayMs;
        if (elapsed >= intervalMs) {
            startCheck(context, true);
            delayMs = intervalMs;
        } else {
            delayMs = intervalMs - elapsed;
        }

        mainHandler.postDelayed(this::scheduleNextCheck, delayMs);
    }

    public void stopScheduledCheck() {
        isScheduledRunning = false;
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void doCheck() {
        int historyIndex = Hawk.get(HawkConfig.HOME_NUM, 0);
        int historyLimit = HistoryHelper.getHisNum(historyIndex);
        List<VodInfo> historyList = RoomDataManger.getAllVodRecord(historyLimit);
        if (historyList == null || historyList.isEmpty()) {
            hasUpdate = false;
            updateCount.set(0);
            cacheLock.writeLock().lock();
            try {
                updateCache.clear();
            } finally {
                cacheLock.writeLock().unlock();
            }
            notifyComplete(false, new HashMap<>());
            return;
        }

        List<VodInfo> toCheckList = new ArrayList<>();
        for (VodInfo vodInfo : historyList) {
            if (!isFinished(vodInfo)) {
                toCheckList.add(vodInfo);
            }
        }

        if (toCheckList.isEmpty()) {
            hasUpdate = false;
            updateCount.set(0);
            cacheLock.writeLock().lock();
            try {
                updateCache.clear();
            } finally {
                cacheLock.writeLock().unlock();
            }
            notifyComplete(false, new HashMap<>());
            return;
        }

        int total = toCheckList.size();
        int current = 0;
        int foundUpdateCount = 0;

        Map<String, Boolean> newCache = new HashMap<>();

        for (VodInfo vodInfo : toCheckList) {
            current++;
            notifyProgress(current, total);

            try {
                String key = vodInfo.sourceKey + "_" + vodInfo.id;
                boolean updated = checkVideoUpdate(vodInfo);
                newCache.put(key, updated);
                if (updated) {
                    foundUpdateCount++;
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notifyError("检测被中断");
                return;
            } catch (Exception e) {
                LOG.e(e);
            }
        }

        cacheLock.writeLock().lock();
        try {
            updateCache.clear();
            updateCache.putAll(newCache);
        } finally {
            cacheLock.writeLock().unlock();
        }

        updateCount.set(foundUpdateCount);
        hasUpdate = foundUpdateCount > 0;
        Hawk.put(UPDATE_CHECK_LAST_TIME, System.currentTimeMillis());
        notifyComplete(hasUpdate, new HashMap<>(newCache));
    }

    private boolean checkVideoUpdate(VodInfo savedVodInfo) {
        if (savedVodInfo == null || TextUtils.isEmpty(savedVodInfo.sourceKey) || TextUtils.isEmpty(savedVodInfo.id)) {
            return false;
        }

        int playIndex = Math.max(savedVodInfo.playIndex, savedVodInfo.playEpisodeIndex);
        
        if (playIndex <= 0 && savedVodInfo.playNote != null && !savedVodInfo.playNote.isEmpty()) {
            playIndex = extractEpisodeNumber(savedVodInfo.playNote);
        }

        try {
            int currentTotalEpisodes = fetchCurrentTotalEpisodes(savedVodInfo.sourceKey, savedVodInfo.id);
            if (currentTotalEpisodes <= 0) {
                return false;
            }

            if (playIndex >= 0) {
                return currentTotalEpisodes > playIndex + 1;
            }
            
            int savedTotalEpisodes = savedVodInfo.totalEpisodes;
            if (savedTotalEpisodes > 0) {
                return currentTotalEpisodes > savedTotalEpisodes;
            }
            
            return false;
        } catch (Exception e) {
            LOG.e(e);
        }

        return false;
    }

    private int extractEpisodeNumber(String playNote) {
        if (playNote == null || playNote.isEmpty()) {
            return -1;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(playNote);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group()) - 1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private int fetchCurrentTotalEpisodes(String sourceKey, String vodId) {
        Future<String> future = null;
        try {
            SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
            if (sourceBean == null) {
                return 0;
            }

            Spider spider = ApiConfig.get().getCSP(sourceBean);
            if (spider == null) {
                return 0;
            }

            List<String> ids = new ArrayList<>();
            ids.add(vodId);

            future = checkExecutor.submit(() -> {
                try {
                    return spider.detailContent(ids);
                } catch (Exception e) {
                    return null;
                }
            });

            String json = future.get(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (TextUtils.isEmpty(json)) {
                return 0;
            }

            return parseTotalEpisodes(json);
        } catch (Exception e) {
            LOG.e(e);
            return 0;
        } finally {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private int parseTotalEpisodes(String json) {
        try {
            AbsJson absJson = gson.fromJson(json, new TypeToken<AbsJson>() {}.getType());
            if (absJson == null || absJson.list == null || absJson.list.isEmpty()) {
                return 0;
            }

            AbsJson.AbsJsonVod jsonVod = absJson.list.get(0);
            if (jsonVod == null) {
                return 0;
            }

            if (TextUtils.isEmpty(jsonVod.vod_play_url)) {
                return 0;
            }

            String[] playUrls = jsonVod.vod_play_url.split("\\$\\$\\$");
            int max = 0;
            for (String playUrl : playUrls) {
                if (TextUtils.isEmpty(playUrl)) {
                    continue;
                }
                String[] episodes;
                if (playUrl.contains("#")) {
                    episodes = playUrl.split("#");
                } else {
                    episodes = new String[]{playUrl};
                }
                int count = 0;
                for (String episode : episodes) {
                    if (!TextUtils.isEmpty(episode)) {
                        count++;
                    }
                }
                if (count > max) {
                    max = count;
                }
            }
            return max;
        } catch (Exception e) {
            LOG.e(e);
            return 0;
        }
    }

    private int getTotalEpisodes(VodInfo vodInfo) {
        if (vodInfo == null || vodInfo.seriesMap == null) {
            return 0;
        }

        int max = 0;
        for (List<VodInfo.VodSeries> seriesList : vodInfo.seriesMap.values()) {
            if (seriesList != null && seriesList.size() > max) {
                max = seriesList.size();
            }
        }
        return max;
    }

    private boolean isFinished(VodInfo vodInfo) {
        if (vodInfo == null) {
            return true;
        }

        int playIndex = Math.max(vodInfo.playIndex, vodInfo.playEpisodeIndex);
        
        if (playIndex <= 0 && vodInfo.playNote != null && !vodInfo.playNote.isEmpty()) {
            playIndex = extractEpisodeNumber(vodInfo.playNote);
        }

        return playIndex < 0;
    }

    private boolean isWifiConnected(Context context) {
        if (context == null) {
            return false;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                return activeNetwork != null && activeNetwork.getType() == ConnectivityManager.TYPE_WIFI;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void notifyComplete(boolean hasUpdate, Map<String, Boolean> updates) {
        mainHandler.post(() -> {
            for (UpdateCheckListener listener : listeners) {
                try {
                    listener.onCheckComplete(hasUpdate, updates);
                } catch (Exception e) {
                    LOG.e(e);
                }
            }
        });
    }

    private void notifyProgress(int current, int total) {
        mainHandler.post(() -> {
            for (UpdateCheckListener listener : listeners) {
                try {
                    listener.onCheckProgress(current, total);
                } catch (Exception e) {
                    LOG.e(e);
                }
            }
        });
    }

    private void notifyError(String errorMessage) {
        mainHandler.post(() -> {
            for (UpdateCheckListener listener : listeners) {
                try {
                    listener.onCheckError(errorMessage);
                } catch (Exception e) {
                    LOG.e(e);
                }
            }
        });
    }

    public void clearCache() {
        cacheLock.writeLock().lock();
        try {
            updateCache.clear();
        } finally {
            cacheLock.writeLock().unlock();
        }
        updateCount.set(0);
        hasUpdate = false;
        Hawk.put(UPDATE_CHECK_LAST_TIME, 0L);
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
    }

    public void shutdown() {
        stopScheduledCheck();
        executor.shutdown();
        checkExecutor.shutdown();
        listeners.clear();
        appContextRef = null;
    }

    /**
     * 重置检测状态，用于更换API后允许立即检测
     */
    public void resetCheckState() {
        isChecking.set(false);
        Hawk.put(UPDATE_CHECK_LAST_TIME, 0L);
    }
}
