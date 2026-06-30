package com.droiddevgeeks.crashsink;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.junit.Test;

public class AndroidDeviceMetadataTest {

    @Test public void collectsAppVersionFromPackageInfo() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        PackageInfo pi = new PackageInfo();
        pi.versionName = "1.2.3";
        pi.versionCode = 42;

        when(context.getPackageManager()).thenReturn(pm);
        when(context.getPackageName()).thenReturn("com.x");
        when(pm.getPackageInfo("com.x", 0)).thenReturn(pi);

        DeviceMetadata md = AndroidDeviceMetadata.collect(context);

        assertEquals("1.2.3", md.appVersionName);
        assertEquals(42, md.appVersionCode);
        // Build.* fields are default/null under unit tests; do not assert on them.
    }

    @Test public void leavesDefaultsWhenPackageManagerThrows() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);

        when(context.getPackageManager()).thenReturn(pm);
        when(context.getPackageName()).thenReturn("com.x");
        when(pm.getPackageInfo("com.x", 0))
                .thenThrow(new PackageManager.NameNotFoundException());

        DeviceMetadata md = AndroidDeviceMetadata.collect(context);

        assertEquals("unknown", md.appVersionName);
        assertEquals(0, md.appVersionCode);
    }
}
