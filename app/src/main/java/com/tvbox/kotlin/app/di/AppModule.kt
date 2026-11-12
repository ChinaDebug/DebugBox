package com.tvbox.kotlin.app.di

import com.github.catvod.crawler.JsSpiderFactory
import com.tvbox.kotlin.app.AppNavigatorImpl
import com.tvbox.kotlin.core.data.VideoDetailFetcher
import com.tvbox.kotlin.core.server.ServerHost
import com.tvbox.kotlin.core.server.ServerResources
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.core.ui.DriveBrowser
import com.tvbox.kotlin.core.ui.PlayResolver
import com.tvbox.kotlin.core.ui.SourceInfoProvider
import com.tvbox.kotlin.engine.quickjs.QuickJsEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 应用层 Hilt 装配，将实现绑定到 core 层契约接口。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindAppNavigator(impl: AppNavigatorImpl): AppNavigator

    @Binds
    @Singleton
    abstract fun bindServerHost(impl: ServerHostImpl): ServerHost

    @Binds
    @Singleton
    abstract fun bindServerResources(impl: ServerResourcesImpl): ServerResources

    @Binds
    @Singleton
    abstract fun bindSourceInfoProvider(impl: SourceInfoProviderImpl): SourceInfoProvider

    @Binds
    @Singleton
    abstract fun bindPlayResolver(impl: PlayResolverImpl): PlayResolver

    @Binds
    @Singleton
    abstract fun bindDriveBrowser(impl: DriveBrowserImpl): DriveBrowser

    @Binds
    @Singleton
    abstract fun bindJsSpiderFactory(impl: JsSpiderFactoryImpl): JsSpiderFactory

    @Binds
    @Singleton
    abstract fun bindVideoDetailFetcher(impl: VideoDetailFetcherImpl): VideoDetailFetcher

    companion object {
        @Provides
        @Singleton
        fun provideQuickJsEngine(): QuickJsEngine = QuickJsEngine()
    }
}
