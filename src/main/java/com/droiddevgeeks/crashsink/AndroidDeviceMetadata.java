package com.droiddevgeeks.crashsink;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

/** Collects {@link DeviceMetadata} from an Android Context (off the crash path). */
public final class AndroidDeviceMetadata {

    private AndroidDeviceMetadata() {
    }

    public static DeviceMetadata collect(final Context context) {
        String appVersionName = "unknown";
        int appVersionCode = 0;
        try {
            final PackageInfo pi =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (pi.versionName != null) {
                appVersionName = pi.versionName;
            }
            appVersionCode = pi.versionCode;
        } catch (Throwable t) {
            // leave defaults
        }
        return new DeviceMetadata(
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                Build.MANUFACTURER, Build.MODEL,
                appVersionName, appVersionCode);
    }
}
