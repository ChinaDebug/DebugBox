package com.github.tvbox.osc.util.parser;

import android.util.Base64;

import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 并发解析，直到获得第一个结果
 */
public class JsonParallel {

    // 统一 OkHttp 客户端，避免每次解析重复创建连接池
    private static volatile OkHttpClient client;
    // 固定大小线程池，复用解析任务线程
    private static volatile ExecutorService executorService;
    // 当前批次任务集合，用于结果出现后取消其他未完成任务
    private static final List<Future<JSONObject>> futures = new ArrayList<>();
    // 标记当前是否正在解析，防止重复进入
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);

    // 根据设备 CPU 核心数动态计算并发线程数：核心数 * 2，最低 4 最高 16
    private static final int MAX_PARALLEL = Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
    // 单个解析接口最大等待时长，避免无效接口长期占用线程（毫秒）
    private static final long TASK_TIMEOUT_MS = 15000;
    // 整轮解析总超时，所有接口失效时避免无限等待（毫秒）
    private static final long TOTAL_TIMEOUT_MS = 20000;

    /**
     * 获取单例 OkHttp 客户端
     */
    private static OkHttpClient getClient() {
        if (client == null) {
            synchronized (JsonParallel.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .writeTimeout(10, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return client;
    }

    /**
     * 获取单例线程池
     */
    private static ExecutorService getExecutor() {
        if (executorService == null || executorService.isShutdown()) {
            synchronized (JsonParallel.class) {
                if (executorService == null || executorService.isShutdown()) {
                    executorService = Executors.newFixedThreadPool(MAX_PARALLEL);
                }
            }
        }
        return executorService;
    }

    public static JSONObject parse(LinkedHashMap<String, String> jx, String url) {
        // 防止上一轮任务未完全结束就开启新一轮
        cancelTasks();
        if (jx == null || jx.isEmpty()) {
            return new JSONObject();
        }
        if (!isRunning.compareAndSet(false, true)) {
            // 已有解析在进行，直接返回空，避免并发冲突
            SpiderDebug.log(new IllegalStateException("JsonParallel 上一轮解析尚未结束"));
            return new JSONObject();
        }

        OkHttpClient currentClient = getClient();
        ExecutorService currentExecutor = getExecutor();
        CompletionService<JSONObject> completionService = new ExecutorCompletionService<>(currentExecutor);

        try {
            synchronized (futures) {
                futures.clear();
                for (final String jxName : jx.keySet()) {
                    final String parseUrl = jx.get(jxName);
                    if (parseUrl == null || parseUrl.isEmpty()) {
                        continue;
                    }
                    futures.add(completionService.submit(new Callable<JSONObject>() {
                        @Override
                        public JSONObject call() {
                            // 任务被中断或已取消时立即退出
                            if (Thread.currentThread().isInterrupted()) {
                                return null;
                            }
                            try {
                                // 获取请求头，并从中取出实际 url
                                HashMap<String, String> reqHeaders = JsonParallel.getReqHeader(parseUrl);
                                String realUrl = reqHeaders.get("url");
                                reqHeaders.remove("url");
                                Headers headers = Headers.of(reqHeaders);
                                Request request = new Request.Builder()
                                        .url(realUrl + url)
                                        .headers(headers)
                                        .tag("ParseTag")
                                        .build();

                                Call call = currentClient.newCall(request);
                                try (Response response = call.execute()) {
                                    if (response.body() == null) {
                                        return null;
                                    }
                                    String json = response.body().string();

                                    JSONObject taskResult = Utils.jsonParse(url, json);
                                    if (taskResult != null) {
                                        taskResult.put("jxFrom", jxName);
                                    }
                                    return taskResult;
                                }
                            } catch (Throwable th) {
                                SpiderDebug.log(th);
                                return null;
                            }
                        }
                    }));
                }
            }

            JSONObject pTaskResult = null;
            long deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS;
            for (int i = 0; i < futures.size(); ++i) {
                long waitMs = deadline - System.currentTimeMillis();
                if (waitMs <= 0) {
                    break;  // 整轮解析总超时，结束等待
                }
                Future<JSONObject> completed;
                try {
                    completed = completionService.poll(waitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (completed == null) {
                    break;  // 总超时，结束等待
                }
                try {
                    pTaskResult = completed.get();
                    if (pTaskResult != null && pTaskResult.has("url") && !pTaskResult.optString("url").isEmpty()) {
                        // 拿到首个有效结果，立即取消其他请求与任务
                        cancelInternal(currentClient);
                        break;
                    }
                } catch (Throwable th) {
                    SpiderDebug.log(th);
                }
            }
            return pTaskResult != null ? pTaskResult : new JSONObject();
        } catch (Throwable th) {
            SpiderDebug.log(th);
            return new JSONObject();
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * 取消当前所有解析任务与网络请求
     */
    public static void cancelTasks() {
        OkHttpClient currentClient = client;
        cancelInternal(currentClient);
    }

    /**
     * 彻底释放线程池与客户端，适合配置切换或页面销毁时调用
     */
    public static void release() {
        cancelTasks();
        synchronized (JsonParallel.class) {
            if (executorService != null) {
                executorService.shutdownNow();
                executorService = null;
            }
            client = null;
        }
        isRunning.set(false);
    }

    private static void cancelInternal(OkHttpClient currentClient) {
        if (currentClient != null) {
            try {
                currentClient.dispatcher().cancelAll();
            } catch (Throwable ignored) {
            }
        }
        synchronized (futures) {
            for (Future<JSONObject> future : futures) {
                try {
                    future.cancel(true);
                } catch (Throwable t) {
                    SpiderDebug.log(t);
                }
            }
            futures.clear();
        }
    }

    public static HashMap<String, String> getReqHeader(String url) {
        HashMap<String, String> reqHeaders = new HashMap<>();
        reqHeaders.put("url", url);
        if (url.contains("cat_ext")) {
            try {
                int start = url.indexOf("cat_ext=");
                int end = url.indexOf("&", start);
                String ext = url.substring(start + 8, end);
                ext = new String(Base64.decode(ext, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP));
                String newUrl = url.substring(0, start) + url.substring(end + 1);
                JSONObject jsonObject = new JSONObject(ext);
                if (jsonObject.has("header")) {
                    JSONObject headerJson = jsonObject.optJSONObject("header");
                    Iterator<String> keys = headerJson.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        reqHeaders.put(key, headerJson.optString(key, ""));
                    }
                }
                reqHeaders.put("url", newUrl);
            } catch (Throwable th) {
                SpiderDebug.log(th);
            }
        }
        return reqHeaders;
    }
}
