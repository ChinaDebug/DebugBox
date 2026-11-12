package com.tvbox.kotlin.app.di

import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.ui.SourceInfoProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 站点源信息查询实现，由 :app 注入到 feature 模块。
 * 通过 [ApiConfig.getSource] 查询站点源名，避免 feature 直接依赖 core:spider。
 */
@Singleton
class SourceInfoProviderImpl @Inject constructor(
    private val apiConfig: ApiConfig
) : SourceInfoProvider {

    override fun getSourceName(sourceKey: String?): String {
        if (sourceKey.isNullOrEmpty()) return ""
        val source = runCatching { apiConfig.getSource(sourceKey) }.getOrNull()
        return source?.name.orEmpty()
    }

    override fun isSourceAvailable(sourceKey: String?): Boolean {
        if (sourceKey.isNullOrEmpty()) return false
        return runCatching { apiConfig.getSource(sourceKey) }.getOrNull() != null
    }
}
