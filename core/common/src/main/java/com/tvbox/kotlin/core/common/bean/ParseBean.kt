package com.tvbox.kotlin.core.common.bean

import com.tvbox.kotlin.core.common.util.DefaultConfig

/** 解析规则配置 */
class ParseBean {

    companion object {
        const val TYPE_SNIFF = 0
        const val TYPE_JSON = 1
        const val TYPE_JSON_EXT = 2
        const val TYPE_AGGREGATE = 3
    }

    var name: String? = null
    private var url: String? = null
    var ext: String = ""
    /** 0 普通嗅探 1 json 2 Json 扩展 3 聚合 */
    var type: Int = 0
    private var isDefault: Boolean = false

    fun getUrl(): String = DefaultConfig.checkReplaceProxy(url ?: "")

    fun setUrl(url: String?) {
        this.url = url
    }

    fun isDefault(): Boolean = isDefault

    fun setDefault(b: Boolean) {
        isDefault = b
    }

    /** 拼接 ext 到 url 的查询参数 */
    fun mixUrl(): String {
        if (ext.isNotEmpty()) {
            val idx = url?.indexOf("?") ?: -1
            if (idx > 0) {
                val encoded = android.util.Base64.encodeToString(
                    ext.toByteArray(),
                    android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                return url!!.substring(0, idx + 1) + "cat_ext=" + encoded + "&" + url!!.substring(idx + 1)
            }
        }
        return url ?: ""
    }
}
