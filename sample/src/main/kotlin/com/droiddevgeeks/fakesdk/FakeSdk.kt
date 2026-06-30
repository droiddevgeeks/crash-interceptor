package com.droiddevgeeks.fakesdk

/**
 * Stand-in for a third-party SDK that the host app integrates.
 *
 * A crash thrown from here has `com.droiddevgeeks.fakesdk.FakeSdk` as its top application
 * frame, so crashsink — configured with `ownedPrefix = "com.droiddevgeeks.fakesdk."` —
 * attributes it to the SDK and captures it.
 */
object FakeSdk {
    fun boom() {
        throw IllegalStateException("FakeSdk exploded while processing a payment")
    }
}
