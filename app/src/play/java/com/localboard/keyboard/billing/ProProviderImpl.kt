package com.localboard.keyboard.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Play distribution: a single one-time purchase.
 *
 * DESIGN NOTE — the entitlement is cached locally and the app never blocks on
 * the network to decide whether you own it. A privacy keyboard that stops
 * working because it could not phone home would be contradicting itself, and
 * the keyboard has to work in aeroplane mode, on a dead network, forever. The
 * store is consulted opportunistically to *grant*; it is never consulted to
 * *revoke*.
 *
 * A determined user can defeat a local cache. That is fine: this is a $2
 * unlock, and the alternative — mandatory online verification on the app that
 * sees everything you type — is a far worse trade for the honest majority.
 */
class ProProviderImpl : ProProvider {

    @Volatile
    private var unlocked = false

    @Volatile
    private var cachedPrice: String? = null

    private var client: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private var appContext: Context? = null
    private var pendingResult: ((Boolean) -> Unit)? = null

    override val isUnlocked: Boolean get() = unlocked
    override val priceLabel: String? get() = cachedPrice

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            var granted = false
            for (p in purchases) {
                if (applyPurchase(p)) granted = true
            }
            pendingResult?.invoke(granted)
        } else {
            // USER_CANCELED is the common case and is not an error worth
            // surfacing beyond "nothing happened".
            pendingResult?.invoke(false)
        }
        pendingResult = null
    }

    override fun initialize(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        // Trust the cache immediately so a launch with no network still knows
        // what it owns; the store may confirm a moment later.
        unlocked = readCachedEntitlement(ctx)
        if (client != null) return

        val c = BillingClient.newBuilder(ctx)
            .setListener(purchasesListener)
            .enablePendingPurchases()
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                queryProduct()
                queryOwned()
            }

            override fun onBillingServiceDisconnected() {
                // Nothing to do. The cached entitlement carries the app, and
                // the next initialize() will reconnect.
            }
        })
    }

    private fun queryProduct() {
        val c = client ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            ).build()
        c.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            productDetails = details.firstOrNull()
            cachedPrice = productDetails
                ?.oneTimePurchaseOfferDetails
                ?.formattedPrice
        }
    }

    private fun queryOwned() {
        val c = client ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        c.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            for (p in purchases) applyPurchase(p)
        }
    }

    /** Record and acknowledge a purchase. Returns true if it granted Pro. */
    private fun applyPurchase(purchase: Purchase): Boolean {
        if (!purchase.products.contains(PRODUCT_ID)) return false
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return false

        unlocked = true
        appContext?.let { writeCachedEntitlement(it, true) }

        // Google refunds an unacknowledged purchase after three days.
        if (!purchase.isAcknowledged) {
            val c = client
            if (c != null) {
                c.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                ) { /* an ack failure is retried on the next launch */ }
            }
        }
        return true
    }

    override fun purchase(activity: Activity, onResult: (Boolean) -> Unit) {
        val c = client
        val details = productDetails
        if (c == null || details == null) {
            onResult(false)
            return
        }
        pendingResult = onResult
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            ).build()
        c.launchBillingFlow(activity, params)
    }

    override fun restore(onResult: (Boolean) -> Unit) {
        val c = client
        if (c == null) { onResult(unlocked); return }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        c.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                for (p in purchases) applyPurchase(p)
            }
            onResult(unlocked)
        }
    }

    // ── Local entitlement cache ──────────────────────────────────────────
    //
    // Separate from the main preference file so a settings restore from a
    // backup cannot carry an unlock onto another device.

    private fun prefs(context: Context) =
        context.getSharedPreferences("localboard_entitlement", Context.MODE_PRIVATE)

    private fun readCachedEntitlement(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_UNLOCKED, false) } catch (_: Throwable) { false }

    private fun writeCachedEntitlement(context: Context, value: Boolean) {
        try { prefs(context).edit().putBoolean(KEY_UNLOCKED, value).apply() } catch (_: Throwable) { }
    }

    private companion object {
        /** Must match the in-app product id created in the Play Console. */
        const val PRODUCT_ID = "localboard_pro"
        const val KEY_UNLOCKED = "pro_unlocked"
    }
}
