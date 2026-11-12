package com.tvbox.kotlin.core.ui

/**
 * 站点源信息查询契约接口。
 *
 * feature 模块禁止直接依赖 core:spider，通过该接口由 :app 模块
 * 注入 ApiConfig.getSource(key)?.getName() 实现查询能力，保持单向依赖。
 *
 * 在 :app 的 Hilt 模块中绑定实现：
 * ```
 * @Module
 * @InstallIn(SingletonComponent::class)
 * abstract class SourceInfoProviderModule {
 *     @Binds @Singleton
 *     abstract fun bindSourceInfoProvider(impl: ApiConfigSourceInfoProvider): SourceInfoProvider
 * }
 * ```
 */
interface SourceInfoProvider {

    /** 根据 sourceKey 查询源显示名，找不到返回空字符串 */
    fun getSourceName(sourceKey: String?): String

    /** 判断 sourceKey 对应的源是否存在 */
    fun isSourceAvailable(sourceKey: String?): Boolean
}
