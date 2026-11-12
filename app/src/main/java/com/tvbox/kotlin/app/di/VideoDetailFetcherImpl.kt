package com.tvbox.kotlin.app.di

import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.data.VideoDetailFetcher
import com.tvbox.kotlin.core.spider.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频更新检测详情抓取实现。
 * 通过 [ApiConfig] 获取站点爬虫并抓取详情 JSON，由 :app 注入到 core:data。
 *
 * 注意：CSP 的 detailContent 是同步阻塞调用，不响应协程取消；
 * 失效/响应慢的接口会长时间占用线程导致检测永久卡在"正在检测更新"。
 * 因此用独立线程执行并通过 get(timeout) 施加 5 秒硬超时，超时立即放弃该条记录。
 */
@Singleton
class VideoDetailFetcherImpl @Inject constructor(
    private val apiConfig: ApiConfig
) : VideoDetailFetcher {

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "video-detail-fetch").apply { isDaemon = true }
    }

    override suspend fun fetchDetail(sourceKey: String, vodId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val source = apiConfig.getSource(sourceKey) ?: return@withContext null
                val spider = apiConfig.getCSP(source) ?: return@withContext null
                val future = executor.submit<String?> {
                    runCatching { spider.detailContent(listOf(vodId)) }.getOrNull()
                }
                try {
                    future.get(DETAIL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    // 阻塞调用无法被取消时线程池最终自行释放，这里直接放弃该条记录
                    future.cancel(true)
                    null
                }
            } catch (e: Exception) {
                LOG.e(e)
                null
            }
        }

    companion object {
        private const val DETAIL_TIMEOUT_SECONDS = 5L
    }
}
