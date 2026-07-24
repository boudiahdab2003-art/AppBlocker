package com.appblocker.data

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

/** The installed app version code, or -1 when package metadata cannot be read. */
object AppVersion {
    fun code(context: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0),
        )
    }.getOrDefault(-1L)
}
