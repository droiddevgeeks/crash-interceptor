package com.droiddevgeeks.crashsink;

/** Immutable device/app metadata, collected once off the crash path. Pure Java (no Android). */
public final class DeviceMetadata {
    public final String osVersion;
    public final int sdkInt;
    public final String manufacturer;
    public final String model;
    public final String appVersionName;
    public final int appVersionCode;

    public DeviceMetadata(final String osVersion, final int sdkInt, final String manufacturer,
                          final String model, final String appVersionName, final int appVersionCode) {
        this.osVersion = osVersion;
        this.sdkInt = sdkInt;
        this.manufacturer = manufacturer;
        this.model = model;
        this.appVersionName = appVersionName;
        this.appVersionCode = appVersionCode;
    }
}
