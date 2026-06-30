package com.droiddevgeeks.crashsink

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AndroidDeviceMetadataTest {

    @Test @Suppress("DEPRECATION") fun collectsAppVersionFromPackageInfo() {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val pi = PackageInfo()
        pi.versionName = "1.2.3"
        pi.versionCode = 42

        `when`(context.packageManager).thenReturn(pm)
        `when`(context.packageName).thenReturn("com.x")
        `when`(pm.getPackageInfo("com.x", 0)).thenReturn(pi)

        val md = AndroidDeviceMetadata.collect(context)

        assertEquals("1.2.3", md.appVersionName)
        assertEquals(42, md.appVersionCode)
        // Build.* fields are default/null under unit tests; do not assert on them.
    }

    @Test fun leavesDefaultsWhenPackageManagerThrows() {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)

        `when`(context.packageManager).thenReturn(pm)
        `when`(context.packageName).thenReturn("com.x")
        `when`(pm.getPackageInfo("com.x", 0)).thenThrow(PackageManager.NameNotFoundException())

        val md = AndroidDeviceMetadata.collect(context)

        assertEquals("unknown", md.appVersionName)
        assertEquals(0, md.appVersionCode)
    }
}
