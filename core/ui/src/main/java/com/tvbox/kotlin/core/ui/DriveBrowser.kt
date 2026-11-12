package com.tvbox.kotlin.core.ui

import com.tvbox.kotlin.core.common.bean.DriveFolderFile

/**
 * 网盘文件浏览契约接口。
 *
 * feature:drive 模块通过此接口浏览网盘文件树，
 * 由 :app 模块提供实现（WebDAV / Alist / 本地）。
 */
interface DriveBrowser {

    /**
     * 列出指定网盘路径下的文件和文件夹。
     *
     * @param drive 网盘根节点（含配置信息）
     * @param path 当前路径节点，null 表示列出根目录
     * @return 子节点列表，失败返回空列表
     */
    suspend fun listFiles(drive: DriveFolderFile, path: DriveFolderFile?): List<DriveFolderFile>
}
