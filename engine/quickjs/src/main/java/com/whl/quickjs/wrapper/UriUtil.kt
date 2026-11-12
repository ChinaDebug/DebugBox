/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.whl.quickjs.wrapper

import android.net.Uri
import android.text.TextUtils

/**
 * URI 解析工具，提供 RFC-3986 相对地址解析、查询参数移除等能力。
 * 保留 com.whl.quickjs.wrapper 包名以兼容 wang.harlon.quickjs:wrapper 依赖的补丁扩展。
 */
object UriUtil {

    private const val INDEX_COUNT = 4
    private const val SCHEME_COLON = 0
    private const val PATH = 1
    private const val QUERY = 2
    private const val FRAGMENT = 3

    /** 以 [baseUri] 解析 [referenceUri]，返回 Uri */
    @JvmStatic
    fun resolveToUri(baseUri: String?, referenceUri: String?): Uri =
        Uri.parse(resolve(baseUri, referenceUri))

    /**
     * 按 RFC-3986 对 [referenceUri] 相对 [baseUri] 进行解析。
     */
    @JvmStatic
    fun resolve(baseUri: String?, referenceUri: String?): String {
        val uri = StringBuilder()
        // null 归一为空串，简化后续逻辑
        val base = baseUri ?: ""
        val ref = referenceUri ?: ""

        val refIndices = getUriIndices(ref)
        if (refIndices[SCHEME_COLON] != -1) {
            // 引用为绝对地址，目标即引用本身
            uri.append(ref)
            removeDotSegments(uri, refIndices[PATH], refIndices[QUERY])
            return uri.toString()
        }

        val baseIndices = getUriIndices(base)
        if (refIndices[FRAGMENT] == 0) {
            // 引用为空或仅含 fragment，目标为去掉 fragment 的 base 拼接引用
            return uri.append(base, 0, baseIndices[FRAGMENT]).append(ref).toString()
        }

        if (refIndices[QUERY] == 0) {
            // 引用以 query 起始，目标为 base 去掉 query 部分再拼接引用
            return uri.append(base, 0, baseIndices[QUERY]).append(ref).toString()
        }

        if (refIndices[PATH] != 0) {
            // 引用含 authority，目标为 base scheme + 引用
            val baseLimit = baseIndices[SCHEME_COLON] + 1
            uri.append(base, 0, baseLimit).append(ref)
            return removeDotSegments(uri, baseLimit + refIndices[PATH], baseLimit + refIndices[QUERY])
        }

        if (ref[refIndices[PATH]] == '/') {
            // 引用路径以 / 起始，目标为 base scheme + authority + 引用
            uri.append(base, 0, baseIndices[PATH]).append(ref)
            return removeDotSegments(uri, baseIndices[PATH], baseIndices[PATH] + refIndices[QUERY])
        }

        // 目标为 base 去掉最后一段后拼接引用，分两种情况
        if (baseIndices[SCHEME_COLON] + 2 < baseIndices[PATH] &&
            baseIndices[PATH] == baseIndices[QUERY]
        ) {
            // base hier-part 仅 authority 且路径为空，需在 authority 后补 '/'
            uri.append(base, 0, baseIndices[PATH]).append('/').append(ref)
            return removeDotSegments(uri, baseIndices[PATH], baseIndices[PATH] + refIndices[QUERY] + 1)
        } else {
            // 否则取 base hier-part 最后一个 '/' 之后拼接引用
            val lastSlashIndex = base.lastIndexOf('/', baseIndices[QUERY] - 1)
            val baseLimit = if (lastSlashIndex == -1) baseIndices[PATH] else lastSlashIndex + 1
            uri.append(base, 0, baseLimit).append(ref)
            return removeDotSegments(uri, baseIndices[PATH], baseLimit + refIndices[QUERY])
        }
    }

    /** URI 是否以 scheme 起始 */
    @JvmStatic
    fun isAbsolute(uri: String?): Boolean =
        uri != null && getUriIndices(uri)[SCHEME_COLON] != -1

    /** 从 URI 中移除指定查询参数 */
    @JvmStatic
    fun removeQueryParameter(uri: Uri, queryParameterName: String): Uri {
        val builder = uri.buildUpon()
        builder.clearQuery()
        for (key in uri.queryParameterNames) {
            if (key != queryParameterName) {
                for (value in uri.getQueryParameters(key)) {
                    builder.appendQueryParameter(key, value)
                }
            }
        }
        return builder.build()
    }

    /**
     * 移除 URI 路径中的 . / .. 段。
     *
     * @param uri    含 URI 的 StringBuilder
     * @param offset 路径起始索引
     * @param limit  路径结束索引（不含）
     */
    private fun removeDotSegments(uri: StringBuilder, offset: Int, limit: Int): String {
        if (offset >= limit) return uri.toString()
        var offset = offset
        if (uri[offset] == '/') {
            // 路径以 / 起始则保留
            offset++
        }
        var segmentStart = offset
        var i = offset
        var currentLimit = limit
        while (i <= currentLimit) {
            val nextSegmentStart: Int
            if (i == currentLimit) {
                nextSegmentStart = i
            } else if (uri[i] == '/') {
                nextSegmentStart = i + 1
            } else {
                i++
                continue
            }
            if (i == segmentStart + 1 && uri[segmentStart] == '.') {
                // "abc/def/./ghi" -> "abc/def/ghi"
                uri.delete(segmentStart, nextSegmentStart)
                currentLimit -= nextSegmentStart - segmentStart
                i = segmentStart
            } else if (i == segmentStart + 2 && uri[segmentStart] == '.' && uri[segmentStart + 1] == '.') {
                // "abc/def/../ghi" -> "abc/ghi"
                val prevSegmentStart = uri.lastIndexOf("/", segmentStart - 2) + 1
                val removeFrom = if (prevSegmentStart > offset) prevSegmentStart else offset
                uri.delete(removeFrom, nextSegmentStart)
                currentLimit -= nextSegmentStart - removeFrom
                segmentStart = prevSegmentStart
                i = prevSegmentStart
            } else {
                i++
                segmentStart = i
            }
        }
        return uri.toString()
    }

    /** 计算 URI 各组成部分的索引 */
    private fun getUriIndices(uriString: String): IntArray {
        val indices = IntArray(INDEX_COUNT)
        if (TextUtils.isEmpty(uriString)) {
            indices[SCHEME_COLON] = -1
            return indices
        }

        // 从右向左确定外层结构: scheme ":" hier-part [ "?" query ] [ "#" fragment ]
        val length = uriString.length
        var fragmentIndex = uriString.indexOf('#')
        if (fragmentIndex == -1) fragmentIndex = length
        var queryIndex = uriString.indexOf('?')
        if (queryIndex == -1 || queryIndex > fragmentIndex) {
            // '#' 在 '?' 前，则 '?' 属于 fragment
            queryIndex = fragmentIndex
        }
        // 斜杠只允许出现在 hier-part，故首个 '/' 之后的冒号属于 hier-part 而非 scheme 分隔符
        var schemeIndexLimit = uriString.indexOf('/')
        if (schemeIndexLimit == -1 || schemeIndexLimit > queryIndex) {
            schemeIndexLimit = queryIndex
        }
        var schemeIndex = uriString.indexOf(':')
        if (schemeIndex > schemeIndexLimit) {
            // '/' 在 ':' 前
            schemeIndex = -1
        }

        // 确定 hier-part 结构: "//" authority path / path
        val hasAuthority = schemeIndex + 2 < queryIndex &&
            uriString[schemeIndex + 1] == '/' &&
            uriString[schemeIndex + 2] == '/'
        val pathIndex: Int = if (hasAuthority) {
            var idx = uriString.indexOf('/', schemeIndex + 3)
            if (idx == -1 || idx > queryIndex) queryIndex else idx
        } else {
            schemeIndex + 1
        }

        indices[SCHEME_COLON] = schemeIndex
        indices[PATH] = pathIndex
        indices[QUERY] = queryIndex
        indices[FRAGMENT] = fragmentIndex
        return indices
    }
}
