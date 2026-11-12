package com.tvbox.kotlin.app.di

import android.content.Context
import com.tvbox.kotlin.core.server.ServerResources
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ServerResources] 实现，从 assets 加载本地服务静态资源。
 */
@Singleton
class ServerResourcesImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ServerResources {

    private val mimeMap = mapOf(
        ".html" to "text/html",
        ".css" to "text/css",
        ".js" to "application/javascript",
        ".ico" to "image/x-icon",
        ".json" to "application/json"
    )

    override fun find(uri: String): ServerResources.Resource? {
        val path = when {
            uri == "/" || uri.isEmpty() -> "server/index.html"
            uri.startsWith("/") -> "server${uri}"
            else -> "server/$uri"
        }
        val mime = mimeMap.entries.firstOrNull { path.endsWith(it.key) }?.value ?: "application/octet-stream"
        return try {
            context.assets.open(path).use { input ->
                val bytes = input.readBytes()
                ServerResources.Resource(mime) { bytes.inputStream() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
