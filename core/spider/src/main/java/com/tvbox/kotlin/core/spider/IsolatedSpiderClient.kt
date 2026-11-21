package com.tvbox.kotlin.core.spider

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 隔离爬虫搜索客户端。
 *
 * 通过绑定 [IsolatedSpiderService] 在 `:spider` 进程执行 jar 搜索；
 * 进程被 native 崩溃杀死时，所有未完成请求统一返回 [Result.ProcessDied]，
 * 由调用方归因并熔断崩溃源，主进程 UI 不受影响。
 */
class IsolatedSpiderClient(context: Context) {

    sealed class Result {
        data class Ok(val json: String) : Result()
        object Failed : Result()

        /** 子进程在请求期间死亡；[inFlightKeys] 为死亡时所有未完成请求的源 key */
        data class ProcessDied(val inFlightKeys: List<String>) : Result()
    }

    private data class Pending(val key: String, val deferred: CompletableDeferred<Result>)

    private val appContext: Context = context.applicationContext
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val requestIds = AtomicLong(0)
    private val connectMutex = Mutex()
    private val replyHandler = Handler(Looper.getMainLooper()) { msg ->
        onReply(msg)
        true
    }
    private val replyMessenger = Messenger(replyHandler)

    @Volatile
    private var messenger: Messenger? = null

    @Volatile
    private var connected = false

    @Volatile
    private var binding = false

    @Volatile
    private var connectWaiter: CompletableDeferred<Boolean>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            messenger = service?.let { Messenger(it) }
            connected = messenger != null
            connectWaiter?.complete(connected)
            connectWaiter = null
        }

        override fun onServiceDisconnected(name: ComponentName?) = handleProcessDied()

        override fun onBindingDied(name: ComponentName?) = handleProcessDied()

        override fun onNullBinding(name: ComponentName?) {
            connected = false
            connectWaiter?.complete(false)
            connectWaiter = null
            unbindAsync()
        }
    }

    /**
     * 在隔离进程执行一次搜索。
     *
     * @param timeoutMs 客户端超时；超时不会中断子进程执行，仅放弃等待
     */
    suspend fun search(
        sourceKey: String,
        api: String,
        ext: String,
        siteJar: String,
        mainJar: String,
        keyword: String,
        proxyPort: Int,
        timeoutMs: Long
    ): Result {
        if (!ensureConnected()) return Result.Failed
        val requestId = requestIds.incrementAndGet()
        val deferred = CompletableDeferred<Result>()
        pending[requestId] = Pending(sourceKey, deferred)
        val sent = try {
            val message = Message.obtain(null, IsolatedSpiderService.MSG_SEARCH).apply {
                replyTo = replyMessenger
                data = Bundle().apply {
                    putLong(IsolatedSpiderService.KEY_REQUEST_ID, requestId)
                    putString(IsolatedSpiderService.KEY_SOURCE_KEY, sourceKey)
                    putString(IsolatedSpiderService.KEY_API, api)
                    putString(IsolatedSpiderService.KEY_EXT, ext)
                    putString(IsolatedSpiderService.KEY_SITE_JAR, siteJar)
                    putString(IsolatedSpiderService.KEY_MAIN_JAR, mainJar)
                    putString(IsolatedSpiderService.KEY_KEYWORD, keyword)
                    putInt(IsolatedSpiderService.KEY_PROXY_PORT, proxyPort)
                }
            }
            val target = messenger
            target?.send(message)
            target != null
        } catch (_: Throwable) {
            false
        }
        if (!sent) {
            pending.remove(requestId)
            handleProcessDied()
            return Result.ProcessDied(listOf(sourceKey))
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(requestId)
        return result ?: Result.Failed
    }

    /** 解绑隔离进程，供客户端销毁时调用 */
    fun release() {
        connected = false
        messenger = null
        if (binding) {
            binding = false
            runCatching { appContext.unbindService(connection) }
        }
    }

    private fun onReply(msg: Message) {
        if (msg.what != IsolatedSpiderService.MSG_RESULT) return
        val data = msg.data ?: return
        val requestId = data.getLong(IsolatedSpiderService.KEY_REQUEST_ID)
        val request = pending.remove(requestId) ?: return
        if (data.getString(IsolatedSpiderService.KEY_ERROR) != null) {
            request.deferred.complete(Result.Failed)
        } else {
            request.deferred.complete(Result.Ok(data.getString(IsolatedSpiderService.KEY_JSON).orEmpty()))
        }
    }

    /** 子进程死亡：让所有未完成请求以 [Result.ProcessDied] 结束，并在下次请求时重新绑定 */
    private fun handleProcessDied() {
        connected = false
        messenger = null
        connectWaiter?.complete(false)
        connectWaiter = null
        if (pending.isNotEmpty()) {
            val keys = pending.values.map { it.key }.distinct()
            val requests = pending.values.toList()
            pending.clear()
            requests.forEach { it.deferred.complete(Result.ProcessDied(keys)) }
        }
        unbindAsync()
    }

    /** 解绑放到主线程执行，避免与绑定流程并发修改连接状态 */
    private fun unbindAsync() {
        replyHandler.post {
            if (binding) {
                binding = false
                runCatching { appContext.unbindService(connection) }
            }
        }
    }

    private suspend fun ensureConnected(): Boolean {
        if (connected) return true
        return connectMutex.withLock {
            if (connected) return@withLock true
            withContext(Dispatchers.Main) {
                if (!binding) {
                    val waiter = CompletableDeferred<Boolean>()
                    connectWaiter = waiter
                    binding = runCatching {
                        appContext.bindService(
                            Intent(appContext, IsolatedSpiderService::class.java),
                            connection,
                            Context.BIND_AUTO_CREATE
                        )
                    }.getOrDefault(false)
                    if (!binding) connectWaiter = null
                }
            }
            if (!binding) return@withLock false
            val waiter = connectWaiter
            if (waiter == null) {
                connected
            } else {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { waiter.await() } == true
            }
        }
    }

    companion object {
        /** 绑定隔离进程超时 */
        private const val CONNECT_TIMEOUT_MS = 8_000L
    }
}
