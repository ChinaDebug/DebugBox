package com.tvbox.kotlin.core.common.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

/** 存储权限判断工具 */
object StoragePermissionUtils {

    /** Android 11+ 且 targetSdk>=30 时检查 MANAGE_EXTERNAL_STORAGE，否则检查传统写权限 */
    @JvmStatic
    fun hasManageExternalStorage(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
        ) {
            return Environment.isExternalStorageManager()
        }
        return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }
}
