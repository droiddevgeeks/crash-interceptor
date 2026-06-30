package com.droiddevgeeks.crashsink

/**
 * Immutable device/app metadata, collected once off the crash path. Pure (no Android).
 *
 * `@JvmField` keeps direct field access (`metadata.osVersion`) for Java callers,
 * matching the original Java class.
 */
class DeviceMetadata(
    // osVersion/manufacturer/model come from android.os.Build and may legitimately be null
    // (and are null under host unit tests), so they stay nullable — matching the original
    // Java class, whose plain String fields enforced no non-null guarantee.
    @JvmField val osVersion: String?,
    @JvmField val sdkInt: Int,
    @JvmField val manufacturer: String?,
    @JvmField val model: String?,
    @JvmField val appVersionName: String,
    @JvmField val appVersionCode: Int
)
