package com.tvbox.kotlin.core.data.di

import android.content.Context
import com.tvbox.kotlin.core.data.dao.CacheDao
import com.tvbox.kotlin.core.data.dao.SearchDao
import com.tvbox.kotlin.core.data.dao.StorageDriveDao
import com.tvbox.kotlin.core.data.dao.VodCollectDao
import com.tvbox.kotlin.core.data.dao.VodRecordDao
import com.tvbox.kotlin.core.data.db.AppDataBase
import com.tvbox.kotlin.core.data.db.AppDataManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

/**
 * 数据层 Hilt 绑定模块。
 * 提供 AppDataBase 与各 DAO；AppDataManager 及 Repository（CacheManager、RoomDataManger、SearchPresenter）
 * 通过 @Inject @Singleton 构造注入自动绑定，无需在此重复声明。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideAppDataBase(@ApplicationContext context: Context): AppDataBase =
        AppDataManager.build(context)

    @Provides
    @Singleton
    fun provideAppDataManager(
        @ApplicationContext context: Context,
        appDataBase: AppDataBase
    ): AppDataManager = AppDataManager(context, appDataBase)

    @Provides
    fun provideCacheDao(appDataManager: AppDataManager): CacheDao =
        appDataManager.getCacheDao()

    @Provides
    fun provideVodRecordDao(appDataManager: AppDataManager): VodRecordDao =
        appDataManager.getVodRecordDao()

    @Provides
    fun provideVodCollectDao(appDataManager: AppDataManager): VodCollectDao =
        appDataManager.getVodCollectDao()

    @Provides
    fun provideStorageDriveDao(appDataManager: AppDataManager): StorageDriveDao =
        appDataManager.getStorageDriveDao()

    @Provides
    fun provideSearchDao(appDataManager: AppDataManager): SearchDao =
        appDataManager.getSearchDao()
}
