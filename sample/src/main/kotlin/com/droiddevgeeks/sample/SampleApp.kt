package com.droiddevgeeks.sample

import android.app.Application
import com.droiddevgeeks.fakesdk.FakeSdk

/**
 * The HOST app. Its only job here is to integrate the guest SDK by calling the SDK's init.
 *
 * crashsink is NOT installed here — it's installed inside [FakeSdk.init], because a real
 * third-party SDK can't assume the host has an Application class. This demo's host happens to
 * have one and inits from it (earliest, catches startup crashes), but the SDK's init is also
 * called again from [MainActivity] to show that double-init is safe.
 */
class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        FakeSdk.init(this)
    }
}
