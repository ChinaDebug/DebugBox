package com.tvbox.kotlin.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tvbox.kotlin.core.data.entity.VodRecord

/**
 * 影片观看记录表操作，包含超量清理与旧记录回收逻辑。
 */
@Dao
interface VodRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: VodRecord): Long

    @Query("select * from vodRecord order by updateTime desc limit :size")
    suspend fun getAll(size: Int): List<VodRecord>

    /** 当前订阅的历史：旧数据（apiUrl 为 null）兼容显示，新数据严格按订阅地址过滤 */
    @Query("select * from vodRecord where (apiUrl is null or apiUrl=:apiUrl) order by updateTime desc limit :size")
    suspend fun getAllByApi(apiUrl: String, size: Int): List<VodRecord>

    @Query("select * from vodRecord where `sourceKey`=:sourceKey and `vodId`=:vodId")
    suspend fun getVodRecord(sourceKey: String, vodId: String): VodRecord?

    @Delete
    suspend fun delete(record: VodRecord): Int

    @Query("select count(*) from vodRecord")
    suspend fun getCount(): Int

    @Query("DELETE FROM vodRecord")
    suspend fun deleteAll()

    /** 保留最新指定条数，其余删除 */
    @Query("DELETE FROM vodRecord where id NOT IN (SELECT id FROM vodRecord ORDER BY updateTime desc LIMIT :size)")
    suspend fun reserver(size: Int): Int

    /** 获取超出保留条数待删除的旧记录 */
    @Query("select * from vodRecord where id NOT IN (SELECT id FROM vodRecord ORDER BY updateTime desc LIMIT :size)")
    suspend fun getOldRecords(size: Int): List<VodRecord>

    /**
     * 清理重复观看记录：按 (sourceKey, vodId) 分组，仅保留每组 id 最大的一条。
     * 表无唯一约束，历史上并发写入可能产生重复记录。
     */
    @Query("DELETE FROM vodRecord WHERE id NOT IN (SELECT MAX(id) FROM vodRecord GROUP BY sourceKey, vodId)")
    suspend fun deleteDuplicates(): Int
}
