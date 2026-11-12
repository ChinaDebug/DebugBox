package com.tvbox.kotlin.core.data.repo

import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.HistoryHelper
import com.tvbox.kotlin.core.common.util.MD5
import com.tvbox.kotlin.core.common.util.StorageDriveType
import com.tvbox.kotlin.core.data.db.AppDataManager
import com.tvbox.kotlin.core.data.entity.StorageDrive
import com.tvbox.kotlin.core.data.entity.VodCollect
import com.tvbox.kotlin.core.data.entity.VodRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room 数据仓库：封装观看记录、收藏、网盘配置的读写与历史记录清理逻辑。
 */
@Singleton
class RoomDataManger @Inject constructor(
    private val appDataManager: AppDataManager,
    private val appPreferences: AppPreferences,
    private val cacheManager: CacheManager
) {

    private val vodRecordDao get() = appDataManager.getVodRecordDao()
    private val vodCollectDao get() = appDataManager.getVodCollectDao()
    private val storageDriveDao get() = appDataManager.getStorageDriveDao()
    private val searchDao get() = appDataManager.getSearchDao()

    /** 观看记录写入互斥锁：get-then-insert 非原子，并发保存会插入重复记录 */
    private val vodRecordMutex = Mutex()

    /** 序列化 VodInfo 时排除 seriesFlags 与 seriesMap，避免剧集结构冗余存储 */
    private val vodInfoStrategy = object : ExclusionStrategy {
        override fun shouldSkipField(field: FieldAttributes): Boolean {
            if (field.declaringClass == VodInfo::class.java) {
                return field.name == "seriesFlags" || field.name == "seriesMap"
            }
            return false
        }

        override fun shouldSkipClass(clazz: Class<*>?): Boolean = false
    }

    private fun getVodInfoGson(): Gson =
        GsonBuilder().addSerializationExclusionStrategy(vodInfoStrategy).create()

    /** 当前订阅地址（API_URL），作为历史/收藏的订阅归属标记 */
    private fun currentApiUrl(): String =
        appPreferences.getString(AppConstants.API_URL, "")

    suspend fun insertVodRecord(sourceKey: String, vodInfo: VodInfo) = vodRecordMutex.withLock {
        val vodId = vodInfo.id ?: return@withLock
        var record = vodRecordDao.getVodRecord(sourceKey, vodId)
        if (record == null) {
            record = VodRecord()
        }
        record.sourceKey = sourceKey
        record.vodId = vodId
        record.updateTime = System.currentTimeMillis()
        record.apiUrl = currentApiUrl()
        record.dataJson = getVodInfoGson().toJson(vodInfo)
        vodRecordDao.insert(record)
    }

    suspend fun getVodInfo(sourceKey: String, vodId: String): VodInfo? {
        val record = vodRecordDao.getVodRecord(sourceKey, vodId)
        val json = record?.dataJson
        try {
            if (!json.isNullOrEmpty()) {
                val info = getVodInfoGson().fromJson<VodInfo>(
                    json,
                    object : TypeToken<VodInfo>() {}.type
                )
                if (info?.name == null) return null
                return info
            }
        } catch (e: Exception) {
            LOG.e(e)
        }
        return null
    }

    suspend fun deleteVodRecord(sourceKey: String, vodInfo: VodInfo) {
        val vodId = vodInfo.id ?: return
        val record = vodRecordDao.getVodRecord(sourceKey, vodId) ?: return
        // 先清理该影片持久化的播放进度缓存，再删除历史记录，
        // 避免历史删除后从推荐/搜索重新播放同一视频仍自动恢复旧进度
        try {
            // 新格式进度键（VOD_PROGRESS_KEY_PREFIX + sourceKey|vodId| 前缀明文）：
            // 清空该影片全部集的进度缓存（重播任何一集都从头开始）
            appPreferences.removeKeysByPrefix(
                AppConstants.VOD_PROGRESS_KEY_PREFIX + "$sourceKey|$vodId|"
            )
            // 兼容旧格式（全键 MD5）：按历史中持久化的 playProgressKey 清理单集进度
            val json = record.dataJson
            if (!json.isNullOrEmpty()) {
                val saved = getVodInfoGson().fromJson<VodInfo>(
                    json,
                    object : TypeToken<VodInfo>() {}.type
                )
                if (saved != null) {
                    val progressKeyToClear = if (saved.playProgressKey.isNotEmpty()) {
                        saved.playProgressKey
                    } else {
                        // 旧版本记录无 playProgressKey：按历史中的线路+集索引构造稳定键清理
                        // （与进度缓存键格式一致：源key|影片id|线路|集索引|集id）
                        val flag = saved.playFlag ?: ""
                        val series = saved.getFlagSeries(flag)?.getOrNull(saved.playEpisodeIndex)
                        val episodeId = series?.episodeId ?: series?.url ?: ""
                        if (episodeId.isEmpty()) "" else "$sourceKey|${saved.id}|$flag|${saved.playEpisodeIndex}|$episodeId"
                    }
                    if (progressKeyToClear.isNotEmpty()) {
                        appPreferences.remove(
                            AppConstants.VOD_PROGRESS_KEY_PREFIX +
                                (MD5.string2MD5(progressKeyToClear) ?: progressKeyToClear)
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // 解析失败不影响删除历史
        }
        vodRecordDao.delete(record)
    }

    /**
     * 获取全部观看记录并按偏好配置的保留条数清理超量旧记录。
     * 失效源由上层自行过滤。
     */
    suspend fun getAllVodRecord(limit: Int): List<VodInfo> = withContext(Dispatchers.IO) {
        try {
            // 清理遗留的重复记录（旧版本并发写入产生的相同影片多行），仅保留每组最新一条
            try {
                vodRecordDao.deleteDuplicates()
            } catch (_: Exception) {
            }
            val count = vodRecordDao.getCount()
            val homeNumIndex = appPreferences.getInt(AppConstants.HOME_NUM, 0)
            val hisNum = HistoryHelper.getHisNum(homeNumIndex)
            if (count > hisNum) {
                val oldRecords = vodRecordDao.getOldRecords(hisNum)
                oldRecords.forEach { record ->
                    try {
                        val oldJson = record.dataJson
                        if (!oldJson.isNullOrEmpty()) {
                            val info = getVodInfoGson().fromJson<VodInfo>(
                                oldJson,
                                object : TypeToken<VodInfo>() {}.type
                            )
                            if (info != null) {
                                cacheManager.deleteVodCache(record.sourceKey, info)
                            }
                        }
                    } catch (_: Exception) {
                        // 单条旧记录解析失败忽略，不影响整体清理
                    }
                }
                vodRecordDao.reserver(hisNum)
            }

            val recordList = vodRecordDao.getAllByApi(currentApiUrl(), limit)
            val vodInfoList = ArrayList<VodInfo>()
            recordList.forEach { record ->
                var info: VodInfo? = null
                try {
                    val itemJson = record.dataJson
                    if (!itemJson.isNullOrEmpty()) {
                        info = getVodInfoGson().fromJson<VodInfo>(
                            itemJson,
                            object : TypeToken<VodInfo>() {}.type
                        )
                        if (info != null) {
                            info.sourceKey = record.sourceKey
                            // 校验关键字段，name/id 缺失视为无效记录
                            if (info.name == null || info.id == null) {
                                info = null
                            }
                        }
                    }
                } catch (_: Exception) {
                    // 单条记录解析失败忽略
                }
                info?.let { vodInfoList.add(it) }
            }
            vodInfoList
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun insertVodCollect(sourceKey: String, vodInfo: VodInfo) {
        val vodId = vodInfo.id ?: return
        val existing = vodCollectDao.getVodCollect(sourceKey, vodId)
        if (existing != null) {
            // 已收藏：仅更新订阅归属为当前订阅（切换订阅后重新收藏归属当前地址）
            if (existing.apiUrl != currentApiUrl()) {
                existing.apiUrl = currentApiUrl()
                vodCollectDao.insert(existing)
            }
            return
        }
        val record = VodCollect()
        record.sourceKey = sourceKey
        record.vodId = vodId
        record.updateTime = System.currentTimeMillis()
        record.apiUrl = currentApiUrl()
        record.name = vodInfo.name
        record.pic = vodInfo.pic
        vodCollectDao.insert(record)
    }

    suspend fun deleteVodCollect(id: Int) {
        vodCollectDao.delete(id)
    }

    suspend fun deleteVodCollect(sourceKey: String, vodInfo: VodInfo) {
        val vodId = vodInfo.id ?: return
        val record = vodCollectDao.getVodCollect(sourceKey, vodId) ?: return
        vodCollectDao.delete(record)
    }

    suspend fun deleteVodCollectAll() {
        vodCollectDao.deleteAll()
    }

    suspend fun deleteVodRecordAll() {
        // 清空历史前先清理各记录持久化的播放进度缓存，避免历史删除后
        // 从推荐/搜索重新播放同一影片仍自动恢复旧进度
        runCatching {
            vodRecordDao.getAll(Int.MAX_VALUE).forEach { record ->
                // 新格式进度键：按"源key|影片id"前缀清空该影片全部集进度
                runCatching {
                    appPreferences.removeKeysByPrefix(
                        AppConstants.VOD_PROGRESS_KEY_PREFIX + "${record.sourceKey}|${record.vodId}|"
                    )
                }
                val json = record.dataJson
                if (!json.isNullOrEmpty()) {
                    runCatching {
                        val saved = getVodInfoGson().fromJson<VodInfo>(
                            json,
                            object : TypeToken<VodInfo>() {}.type
                        )
                        if (saved != null) {
                            // 兼容旧格式（全键 MD5）：按记录中持久化的 playProgressKey 清理单集进度
                            val progressKeyToClear = if (saved.playProgressKey.isNotEmpty()) {
                                saved.playProgressKey
                            } else {
                                // 旧版本记录无 playProgressKey：按历史中的线路+集索引构造稳定键清理
                                val flag = saved.playFlag ?: ""
                                val series = saved.getFlagSeries(flag)?.getOrNull(saved.playEpisodeIndex)
                                val episodeId = series?.episodeId ?: series?.url ?: ""
                                if (episodeId.isEmpty()) "" else "${record.sourceKey}|${saved.id}|$flag|${saved.playEpisodeIndex}|$episodeId"
                            }
                            if (progressKeyToClear.isNotEmpty()) {
                                appPreferences.remove(
                                    AppConstants.VOD_PROGRESS_KEY_PREFIX +
                                        (MD5.string2MD5(progressKeyToClear) ?: progressKeyToClear)
                                )
                            }
                        }
                    }
                }
            }
        }
        vodRecordDao.deleteAll()
    }

    suspend fun isVodCollect(sourceKey: String, vodId: String): Boolean =
        vodCollectDao.getVodCollect(sourceKey, vodId) != null

    suspend fun getAllVodCollect(): List<VodCollect> = vodCollectDao.getAllByApi(currentApiUrl())

    suspend fun insertDriveRecord(name: String, type: StorageDriveType.TYPE, config: JsonObject?) {
        val drive = StorageDrive()
        drive.name = name
        drive.type = type.ordinal
        drive.configJson = config?.toString()
        storageDriveDao.insert(drive)
    }

    suspend fun updateDriveRecord(drive: StorageDrive) {
        storageDriveDao.insert(drive)
    }

    suspend fun getAllDrives(): List<StorageDrive> = storageDriveDao.getAll()

    suspend fun deleteDrive(id: Int) {
        storageDriveDao.delete(id)
    }

    suspend fun deleteSearchHistoryAll() {
        searchDao.deleteAll()
    }

    suspend fun deleteStorageDriveAll() {
        val drives = storageDriveDao.getAll()
        drives.forEach { storageDriveDao.delete(it.id) }
    }
}
