package com.appblocker.data

import android.content.Context
import android.provider.Settings

/** Android's persistent boot sequence number, or -1 when it cannot be read. */
object DeviceBoot {
    @Volatile private var cachedCount: Int? = null

    fun count(context: Context): Int = cachedCount ?: synchronized(this) {
        cachedCount ?: Settings.Global.getInt(
            context.contentResolver, Settings.Global.BOOT_COUNT, -1,
        ).also { cachedCount = it }
    }
}
