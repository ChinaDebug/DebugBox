package com.tvbox.kotlin.core.data.entity

import androidx.annotation.NonNull
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * 通用缓存表，以 key 为主键存储序列化后的二进制数据。
 */
@Entity(tableName = "cache")
data class Cache(
    @PrimaryKey(autoGenerate = false)
    @field:NonNull
    var key: String = "",
    var data: ByteArray? = null
) : Serializable {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Cache) return false
        return key == other.key && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        return result
    }
}
