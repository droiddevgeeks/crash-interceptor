package com.droiddevgeeks.crashsink

import android.content.Context
import android.os.Build

/** Collects [DeviceMetadata] from an Android Context (off the crash path). Internal. */
internal object AndroidDeviceMetadata {

    fun collect(context: Context): DeviceMetadata {
        var appVersionName = "unknown"
        var appVersionCode = 0
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = pi.versionName
            if (name != null) {
                appVersionName = name
            }
            @Suppress("DEPRECATION")
            appVersionCode = pi.versionCode
        } catch (t: Throwable) {
            // leave defaults
        }
        return DeviceMetadata(
            Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            Build.MANUFACTURER, Build.MODEL,
            appVersionName, appVersionCode
        )
    }
}
