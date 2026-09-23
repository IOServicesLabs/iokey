package com.localboard.keyboard.billing

import android.content.Context

/**
 * What costs money, and what never will.
 *
 * THE LINE
 * --------
 * The free keyboard is a complete, genuinely private keyboard. Everything
 * that protects the user is free forever: the network kill switch, the egress
 * ledger, incognito handling, per-app rules, encryption, backup and export,
 * and every accessibility affordance. Paywalling privacy would make it a
 * luxury good and destroy the only thing this app is actually selling.
 *
 * Pro is the *expensive-to-build, nice-to-have* layer: the on-device AI, and
 * the deep cosmetic surface. Someone who never pays still gets autocorrect,
 * glide typing, offline voice, every layout, and a keyboard that keeps its
 * mouth shut.
 *
 * ONE PLACE
 * ---------
 * Every gate in the app asks this object. If the free/paid line ever needs to
 * move it moves here, not across a dozen call sites that have quietly drifted
 * apart.
 */
enum class ProFeature(val label: String, val blurb: String) {
    AI_DRAFTING(
        "AI writing",
        "Draft and rewrite replies with the on-device model, in your own voice."
    ),
    CUSTOM_THEMES(
        "Theme Studio",
        "Build and save your own colour themes, or import one from a share code."
    ),
    PER_APP_THEMES(
        "Per-app themes",
        "A different keyboard look in each app."
    ),
    ANIMATED_BACKGROUND(
        "Animated backgrounds",
        "Drifting colour, aurora, waves, motes, or a bloom that reacts to typing."
    ),
    CUSTOM_FONT(
        "Your own font",
        "Load any .ttf or .otf for the key labels."
    );
}

/**
 * Per-distribution entitlement source. Implemented in src/play (Play Billing)
 * and src/full (always unlocked).
 */
interface ProProvider {
    /** True when the Pro unlock has been purchased. Must be readable off the
     *  main thread and must never block: gates sit on UI paths. */
    val isUnlocked: Boolean

    /** Displayable price, e.g. "$2.00", or null before the store answers. */
    val priceLabel: String?

    /** Warm up billing / restore a previous purchase. Safe to call repeatedly. */
    fun initialize(context: Context)

    /** Start the purchase flow. Requires an Activity for the billing sheet. */
    fun purchase(activity: android.app.Activity, onResult: (Boolean) -> Unit)

    /** Re-query entitlements, e.g. after a reinstall. */
    fun restore(onResult: (Boolean) -> Unit)
}

object Pro {

    /** Set once at process start by LocalBoardApp. */
    @Volatile
    private var provider: ProProvider? = null

    fun install(p: ProProvider, context: Context) {
        provider = p
        p.initialize(context)
    }

    val isUnlocked: Boolean get() = provider?.isUnlocked ?: false

    val priceLabel: String? get() = provider?.priceLabel

    fun has(feature: ProFeature): Boolean = isUnlocked

    fun purchase(activity: android.app.Activity, onResult: (Boolean) -> Unit) {
        val p = provider
        if (p == null) { onResult(false); return }
        p.purchase(activity, onResult)
    }

    fun restore(onResult: (Boolean) -> Unit) {
        val p = provider
        if (p == null) { onResult(false); return }
        p.restore(onResult)
    }

    /**
     * Gate a feature from a UI entry point. Returns true when the caller may
     * proceed; otherwise opens the upgrade screen and returns false.
     *
     * Deliberately not silent. A control that does nothing when tapped reads
     * as broken software, which is a worse outcome than an honest price tag.
     */
    fun require(context: Context, feature: ProFeature): Boolean {
        if (has(feature)) return true
        context.startActivity(
            android.content.Intent(context, ProActivityRoute.target())
                .putExtra(EXTRA_FEATURE, feature.name)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return false
    }

    const val EXTRA_FEATURE = "pro_feature"
}

/** Indirection so [Pro] does not depend on the settings package. */
object ProActivityRoute {
    @Volatile
    var targetClass: Class<*>? = null

    fun target(): Class<*> = targetClass
        ?: com.localboard.keyboard.settings.SettingsActivity::class.java
}
