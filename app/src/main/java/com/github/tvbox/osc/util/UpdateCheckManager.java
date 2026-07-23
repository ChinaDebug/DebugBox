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
import java.util.concurrent.ConcurrentHashMap;
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
    private static final int CHECK_TOTAL_TIMEOUT_SECONDS = 45;

    // 集数数字提取正则，按优先级排列，越明确的格式越靠前
    private static final java.util.regex.Pattern[] EPISODE_NUMBER_PATTERNS = {
            java.util.regex.Pattern.compile("第([\\d零一二两三四五六七八九十百千万]+)[集话章节回期]"),
            java.util.regex.Pattern.compile("(?i)EP(\\d+)"),
            java.util.regex.Pattern.compile("(?i)\\bE(\\d+)\\b"),
            java.util.regex.Pattern.compile("(?i)S\\d+E(\\d+)"),
            java.util.regex.Pattern.compile("(?i)Vol(?:ume)?\\.?\\s*(\\d+)"),
            java.util.regex.Pattern.compile("_(\\d+)\\s*$"),
            java.util.regex.Pattern.compile("_(\\d+)"),
            java.util.regex.Pattern.compile("\\.(\\d+)\\s*$"),
            java.util.regex.Pattern.compile("\\s(\\d+)\\s*$"),
            java.util.regex.Pattern.compile("^[\\s\\-]*(\\d+)\\s*$"),
            java.util.regex.Pattern.compile("-(\\d+)\\s*$")
    };

    // 非主线集数的关键字，仅在无明确模式时用于兜底过滤
    private static final String[] EPISODE_NOISE_KEYWORDS = {
            "预告", "片花", "花絮", "彩蛋", "总集", "ova", "oad", "sp", "pv"
    };

    // 中文数字与单位映射，用于支持 "第一集"、"第十二集" 等中文集数命名
    private static final java.util.Map<Character, Integer> CHINESE_DIGITS = new java.util.HashMap<>();
    private static final java.util.Map<Character, Integer> CHINESE_UNITS = new java.util.HashMap<>();

    static {
        CHINESE_DIGITS.put('零', 0);
        CHINESE_DIGITS.put('一', 1);
        CHINESE_DIGITS.put('二', 2);
        CHINESE_DIGITS.put('两', 2);
        CHINESE_DIGITS.put('三', 3);
        CHINESE_DIGITS.put('四', 4);
        CHINESE_DIGITS.put('五', 5);
        CHINESE_DIGITS.put('六', 6);
        CHINESE_DIGITS.put('七', 7);
        CHINESE_DIGITS.put('八', 8);
        CHINESE_DIGITS.put('九', 9);
        CHINESE_UNITS.put('十', 10);
        CHINESE_UNITS.put('百', 100);
        CHINESE_UNITS.put('千', 1000);
        CHINESE_UNITS.put('万', 10000);
    }

    private static volatile UpdateCheckManager instance;
    private final ExecutorService executor;
    private final ThreadPoolExecutor checkExecutor;
    private final ThreadPoolExecutor detailExecutor;
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
            3, 10, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUE_SIZE),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        detailExecutor = new ThreadPoolExecutor(
            3, 15, 60L, TimeUnit.SECONDS,
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
            checkExecutor.setCorePoolSize(8);
        } else {
            checkExecutor.setCorePoolSize(5);
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
            Hawk.put(UPDATE_CHECK_LAST_TIME, System.currentTimeMillis());
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
            Hawk.put(UPDATE_CHECK_LAST_TIME, System.currentTimeMillis());
            notifyComplete(false, new HashMap<>());
            return;
        }

        // 先记录检测时间，避免定时调度在检测期间再次触发
        Hawk.put(UPDATE_CHECK_LAST_TIME, System.currentTimeMillis());

        int total = toCheckList.size();
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger foundUpdateCount = new AtomicInteger(0);
        Map<String, Boolean> newCache = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>(total);

        for (VodInfo vodInfo : toCheckList) {
            Future<?> future = checkExecutor.submit(() -> {
                try {
                    String key = vodInfo.sourceKey + "_" + vodInfo.id;
                    boolean updated = checkVideoUpdate(vodInfo);
                    newCache.put(key, updated);
                    if (updated) {
                        foundUpdateCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    LOG.e(e);
                } finally {
                    notifyProgress(completed.incrementAndGet(), total);
                }
            });
            futures.add(future);
        }

        // 使用整体超时，避免前面任务排队等待时就被单任务超时取消
        long deadline = System.currentTimeMillis() + CHECK_TOTAL_TIMEOUT_SECONDS * 1000L;
        for (Future<?> future : futures) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                future.cancel(true);
                continue;
            }
            try {
                future.get(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
            } catch (Exception e) {
                LOG.e(e);
                future.cancel(true);
            }
        }

        cacheLock.writeLock().lock();
        try {
            updateCache.clear();
            updateCache.putAll(newCache);
        } finally {
            cacheLock.writeLock().unlock();
        }

        updateCount.set(foundUpdateCount.get());
        hasUpdate = foundUpdateCount.get() > 0;
        notifyComplete(hasUpdate, new HashMap<>(newCache));
    }

    private boolean checkVideoUpdate(VodInfo savedVodInfo) {
        if (savedVodInfo == null || TextUtils.isEmpty(savedVodInfo.sourceKey) || TextUtils.isEmpty(savedVodInfo.id)) {
            return false;
        }

        try {
            String json = fetchDetailJson(savedVodInfo.sourceKey, savedVodInfo.id);
            if (TextUtils.isEmpty(json)) {
                return false;
            }

            AbsJson absJson = gson.fromJson(json, new TypeToken<AbsJson>() {}.getType());
            if (absJson == null || absJson.list == null || absJson.list.isEmpty()) {
                return false;
            }
            AbsJson.AbsJsonVod jsonVod = absJson.list.get(0);
            if (jsonVod == null || TextUtils.isEmpty(jsonVod.vod_play_url)) {
                return false;
            }

            // 优先按集名数字判断，过滤特别篇/预告等干扰
            int currentEpisodeNumber = parseCurrentEpisodeNumber(jsonVod, savedVodInfo.playFlag, savedVodInfo.playEpisodeName);
            if (currentEpisodeNumber > 0) {
                int playedEpisodeNumber = extractEpisodeNumberRaw(savedVodInfo.playEpisodeName);
                if (playedEpisodeNumber > 0) {
                    return currentEpisodeNumber > playedEpisodeNumber;
                }
            }

            // 回退到按数量判断
            int currentTotalEpisodes = parseTotalEpisodes(jsonVod, savedVodInfo.playFlag);
            if (currentTotalEpisodes <= 0) {
                return false;
            }

            int playIndex = Math.max(savedVodInfo.playIndex, savedVodInfo.playEpisodeIndex);
            if (playIndex <= 0 && savedVodInfo.playNote != null && !savedVodInfo.playNote.isEmpty()) {
                playIndex = extractEpisodeNumber(savedVodInfo.playNote);
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
        int number = extractEpisodeNumberRaw(playNote);
        return number > 0 ? number - 1 : -1;
    }

    private String fetchDetailJson(String sourceKey, String vodId) {
        Future<String> future = null;
        try {
            SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
            if (sourceBean == null) {
                return null;
            }

            Spider spider = ApiConfig.get().getCSP(sourceBean);
            if (spider == null) {
                return null;
            }

            List<String> ids = new ArrayList<>();
            ids.add(vodId);

            // 放到独立线程池执行并设置超时，避免单个慢源拖住 checkExecutor
            future = detailExecutor.submit(() -> {
                try {
                    return spider.detailContent(ids);
                } catch (Exception e) {
                    return null;
                }
            });
            return future.get(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.e(e);
            return null;
        } finally {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private int parseCurrentEpisodeNumber(AbsJson.AbsJsonVod jsonVod, String playFlag, String playedEpisodeName) {
        try {
            if (jsonVod == null || TextUtils.isEmpty(jsonVod.vod_play_url)) {
                return 0;
            }

            if (TextUtils.isEmpty(playFlag) || TextUtils.isEmpty(jsonVod.vod_play_from)) {
                return 0;
            }

            String[] playUrls = jsonVod.vod_play_url.split("\\$\\$\\$");
            String[] playFlags = jsonVod.vod_play_from.split("\\$\\$\\$");
            String targetPlayUrl = null;
            for (int i = 0; i < playFlags.length && i < playUrls.length; i++) {
                if (playFlag.equals(playFlags[i])) {
                    targetPlayUrl = playUrls[i];
                    break;
                }
            }
            if (TextUtils.isEmpty(targetPlayUrl)) {
                return 0;
            }

            java.util.regex.Pattern pattern = inferEpisodePattern(playedEpisodeName);
            String[] episodes = targetPlayUrl.contains("#") ? targetPlayUrl.split("#") : new String[]{targetPlayUrl};

            int maxNumber = 0;
            for (String episode : episodes) {
                if (TextUtils.isEmpty(episode)) {
                    continue;
                }
                String episodeName = extractEpisodeName(episode);
                if (TextUtils.isEmpty(episodeName)) {
                    continue;
                }
                if (pattern != null && !pattern.matcher(episodeName).matches()) {
                    continue;
                }
                // 无明确模式时，过滤掉预告/花絮/特别篇等非主线干扰项
                if (pattern == null && isNoiseEpisode(episodeName)) {
                    continue;
                }
                int number = extractEpisodeNumberRaw(episodeName);
                if (number > maxNumber) {
                    maxNumber = number;
                }
            }
            return maxNumber;
        } catch (Exception e) {
            LOG.e(e);
            return 0;
        }
    }

    private java.util.regex.Pattern inferEpisodePattern(String episodeName) {
        if (TextUtils.isEmpty(episodeName)) {
            return null;
        }
        // 优先匹配带明确集数标识的模式，避免片名中的数字被误判
        if (episodeName.matches(".*第[\\d零一二两三四五六七八九十百千万]+[集话章节回期].*")) {
            return java.util.regex.Pattern.compile(".*第[\\d零一二两三四五六七八九十百千万]+[集话章节回期].*");
        }
        if (episodeName.matches("(?i).*EP\\d+.*")) {
            return java.util.regex.Pattern.compile(".*(?i)EP\\d+.*");
        }
        if (episodeName.matches("(?i).*\\bE\\d+\\b.*")) {
            return java.util.regex.Pattern.compile(".*(?i)\\bE\\d+\\b.*");
        }
        if (episodeName.matches("(?i).*S\\d+E\\d+.*")) {
            // 兼容美剧 "S01E01" 类命名
            return java.util.regex.Pattern.compile(".*(?i)S\\d+E\\d+.*");
        }
        if (episodeName.matches("(?i).*Vol(?:ume)?\\.?\\s*\\d+.*")) {
            // 兼容 "Vol.1" / "Volume 1" 类命名
            return java.util.regex.Pattern.compile(".*(?i)Vol(?:ume)?\\.?\\s*\\d+.*");
        }
        if (episodeName.matches(".*_\\d+.*")) {
            // 兼容 "斗罗大陆2绝世唐门_001" 这类下划线分隔的集数命名
            return java.util.regex.Pattern.compile(".*_\\d+.*");
        }
        if (episodeName.matches("^\\s*-?\\d+\\s*$")) {
            return java.util.regex.Pattern.compile("^\\s*-?\\d+\\s*$");
        }
        if (episodeName.matches(".*\\.\\d+\\s*$")) {
            // 兼容 "name.01" 类命名
            return java.util.regex.Pattern.compile(".*\\.\\d+\\s*$");
        }
        if (episodeName.matches(".*\\s\\d+\\s*$")) {
            // 兼容 "name 01" 类命名
            return java.util.regex.Pattern.compile(".*\\s\\d+\\s*$");
        }
        if (episodeName.matches(".*-\\d+\\s*$")) {
            return java.util.regex.Pattern.compile(".*-\\d+\\s*$");
        }
        return null;
    }

    private String extractEpisodeName(String episodeUrl) {
        if (TextUtils.isEmpty(episodeUrl)) {
            return null;
        }
        int index = episodeUrl.indexOf('$');
        if (index >= 0) {
            return episodeUrl.substring(0, index);
        }
        return episodeUrl;
    }

    /**
     * 判断集数名是否为预告/花絮/特别篇等非主线干扰项
     */
    private boolean isNoiseEpisode(String episodeName) {
        if (TextUtils.isEmpty(episodeName)) {
            return false;
        }
        String lower = episodeName.toLowerCase();
        for (String keyword : EPISODE_NOISE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int extractEpisodeNumberRaw(String name) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        String trimmed = name.trim();
        // 按优先级尝试明确的集数格式，越明确的格式越靠前
        for (java.util.regex.Pattern pattern : EPISODE_NUMBER_PATTERNS) {
            java.util.regex.Matcher matcher = pattern.matcher(trimmed);
            if (matcher.find()) {
                try {
                    String group = matcher.group(1);
                    if (group != null && !group.isEmpty()) {
                        char first = group.charAt(0);
                        if (first >= '0' && first <= '9') {
                            return Integer.parseInt(group);
                        } else {
                            return chineseNumberToInt(group);
                        }
                    }
                } catch (NumberFormatException e) {
                    // 继续尝试下一个模式
                }
            }
        }
        // 兜底：取最后一个连续数字，但排除疑似年份，避免把片名/年份数字误判为集数
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(trimmed);
        int lastNumber = 0;
        while (matcher.find()) {
            try {
                int number = Integer.parseInt(matcher.group());
                if (number >= 1900 && number <= 2099) {
                    continue;
                }
                lastNumber = number;
            } catch (NumberFormatException e) {
                // 忽略超长数字
            }
        }
        return lastNumber;
    }

    /**
     * 将中文数字字符串转换为阿拉伯数字，支持零到万级
     */
    private int chineseNumberToInt(String chinese) {
        if (chinese == null || chinese.isEmpty()) {
            return 0;
        }
        int result = 0;
        int current = 0;
        for (int i = 0; i < chinese.length(); i++) {
            char c = chinese.charAt(i);
            Integer digit = CHINESE_DIGITS.get(c);
            if (digit != null) {
                current = digit;
            } else {
                Integer unit = CHINESE_UNITS.get(c);
                if (unit != null) {
                    // 单位前若无数字，按一处理，如 "十"、"百"
                    if (current == 0) {
                        current = 1;
                    }
                    result += current * unit;
                    current = 0;
                }
            }
        }
        return result + current;
    }

    private int parseTotalEpisodes(AbsJson.AbsJsonVod jsonVod, String playFlag) {
        try {
            if (jsonVod == null || TextUtils.isEmpty(jsonVod.vod_play_url)) {
                return 0;
            }

            String[] playUrls = jsonVod.vod_play_url.split("\\$\\$\\$");
            if (TextUtils.isEmpty(playFlag) || TextUtils.isEmpty(jsonVod.vod_play_from)) {
                return 0;
            }

            String[] playFlags = jsonVod.vod_play_from.split("\\$\\$\\$");
            for (int i = 0; i < playFlags.length && i < playUrls.length; i++) {
                if (playFlag.equals(playFlags[i])) {
                    return countEpisodes(playUrls[i]);
                }
            }
            return 0;
        } catch (Exception e) {
            LOG.e(e);
            return 0;
        }
    }

    private int countEpisodes(String playUrl) {
        if (TextUtils.isEmpty(playUrl)) {
            return 0;
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
        return count;
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

        // 只跳过从未播放过的记录
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
            Network network = cm.getActiveNetwork();
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
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
        detailExecutor.shutdown();
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
