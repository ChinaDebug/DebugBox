package com.tvbox.kotlin.core.data.repo

import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.core.common.util.MD5
import com.tvbox.kotlin.core.data.db.AppDataManager
import com.tvbox.kotlin.core.data.entity.Cache
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 缓存读写管理器：以 Java 序列化形式将对象存入 cache 表。
 */
@Singleton
class CacheManager @Inject constructor(
    private val appDataManager: AppDataManager
) {

    private val cacheDao get() = appDataManager.getCacheDao()

    /** 反序列化二进制为对象 */
    private fun toObject(data: ByteArray): Any? {
        return try {
            ByteArrayInputStream(data).use { bais ->
                ObjectInputStream(bais).use { ois ->
                    ois.readObject()
                }
            }
        } catch (e: Exception) {
            LOG.e(e)
            null
        }
    }

    /** 序列化对象为二进制 */
    private fun <T> toByteArray(body: T): ByteArray {
        return try {
            ByteArrayOutputStream().use { baos ->
                ObjectOutputStream(baos).use { oos ->
                    oos.writeObject(body)
                    oos.flush()
                }
                baos.toByteArray()
            }
        } catch (e: Exception) {
            LOG.e(e)
            ByteArray(0)
        }
    }

    suspend fun <T> delete(key: String, body: T) {
        val cache = Cache(key = key, data = toByteArray(body))
        cacheDao.delete(cache)
    }

    suspend fun <T> save(key: String, body: T) {
        val cache = Cache(key = key, data = toByteArray(body))
        cacheDao.save(cache)
    }

    suspend fun getCache(key: String): Any? {
        val cache = cacheDao.getCache(key)
        return cache?.data?.let { toObject(it) }
    }

    suspend fun deleteAll() {
        cacheDao.deleteAll()
    }

    suspend fun deleteByKeyPrefix(keyPrefix: String) {
        cacheDao.deleteByKeyPrefix(keyPrefix)
    }

    /** 清除指定影片在各线路各集的播放进度缓存 */
    suspend fun deleteVodCache(sourceKey: String?, vodInfo: VodInfo?) {
        if (sourceKey.isNullOrEmpty() || vodInfo == null || vodInfo.id.isNullOrEmpty()) return
        val seriesMap = vodInfo.seriesMap ?: return
        seriesMap.forEach { (playFlag, seriesList) ->
            seriesList?.forEach { vs ->
                val episodeId = vs.episodeId ?: return@forEach
                val progressKey = sourceKey + vodInfo.id + playFlag + episodeId
                val md5 = MD5.string2MD5(progressKey) ?: return@forEach
                delete(md5, 0)
            }
        }
    }
}
