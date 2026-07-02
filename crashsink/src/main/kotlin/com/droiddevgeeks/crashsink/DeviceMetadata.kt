package com.droiddevgeeks.crashsink

/**
 * Immutable device/app metadata, collected once off the crash path. Pure (no Android).
 *
 * Internal: consumers never see this type. It is serialized to the `contexts` JSON string the
 * [CrashSink] receives; it is not part of the consumer-facing API.
 */
internal class DeviceMetadata(
    // osVersion/manufacturer/model come from android.os.Build and may legitimately be null
    // (and are null under host unit tests), so they stay nullable.
    val osVersion: String?,
    val sdkInt: Int,
    val manufacturer: String?,
    val model: String?,
    val appVersionName: String,
    val appVersionCode: Int
)
