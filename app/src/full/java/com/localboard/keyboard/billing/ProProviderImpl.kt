package com.localboard.keyboard.billing

import android.content.Context

/**
 * Full (sideload) distribution: everything is unlocked.
 *
 * There is no store here, so there is nothing to sell and nothing to check.
 * Rather than ship a disabled billing client or a licence check that would
 * only ever get in the way, this build simply reports Pro as owned.
 *
 * The upgrade screen still exists and still works — it just has nothing to
 * offer, and says so.
 */
class ProProviderImpl : ProProvider {

    override val isUnlocked: Boolean = true

    override val priceLabel: String? = null

    override fun initialize(context: Context) = Unit

    override fun purchase(activity: android.app.Activity, onResult: (Boolean) -> Unit) {
        onResult(true)
    }

    override fun restore(onResult: (Boolean) -> Unit) {
        onResult(true)
    }
}
