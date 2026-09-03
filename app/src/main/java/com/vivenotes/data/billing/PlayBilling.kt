package com.vivenotes.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.resume

internal data class PlayPurchase(
    val purchaseToken: String,
    val productId: String,
)

internal data class PlayBillingSnapshot(
    val formattedPrice: String?,
    val purchases: List<PlayPurchase>,
    val pending: Boolean,
    val productAvailable: Boolean,
)

internal enum class PlayBillingFailure {
    Unavailable,
    Network,
    ProductUnavailable,
    DeveloperError,
    AlreadyOwned,
    Unknown,
}

internal sealed interface PlayBillingUpdate {
    data class Purchases(
        val completed: List<PlayPurchase>,
        val pending: Boolean,
    ) : PlayBillingUpdate

    data object Canceled : PlayBillingUpdate
    data class Failed(val reason: PlayBillingFailure) : PlayBillingUpdate
}

internal sealed interface PlayBillingLaunch {
    data object Started : PlayBillingLaunch
    data object AlreadyOwned : PlayBillingLaunch
    data class Failed(val reason: PlayBillingFailure) : PlayBillingLaunch
}

/** Small seam around the Play Store so lifecycle logic can be tested without a licensed device. */
internal interface PlayBillingStore {
    val updates: Flow<PlayBillingUpdate>

    suspend fun refresh(): Result<PlayBillingSnapshot>

    fun launch(activity: Activity, obfuscatedAccountId: String): PlayBillingLaunch
}

/**
 * The process-owned Google Play Billing connection.
 *
 * Product details are intentionally kept only in memory. Google warns against caching them because
 * an offer can become stale and then fail at launch; [refresh] re-queries before the Account screen
 * presents its action. Purchase tokens likewise never enter preferences or logs. Play can restore
 * them, and the managed backend keeps the protected copy needed for lifecycle reconciliation.
 */
internal class GooglePlayBillingStore(
    context: Context,
    private val productId: String,
) : PlayBillingStore, PurchasesUpdatedListener {

    private val mutableUpdates = MutableSharedFlow<PlayBillingUpdate>(extraBufferCapacity = 8)
    override val updates: Flow<PlayBillingUpdate> = mutableUpdates

    private val connectionMutex = Mutex()
    private var productDetails: ProductDetails? = null
    private var offerToken: String? = null

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        // Required by BillingClient. This plan is auto-renewing; no prepaid-plan pending flow is
        // enabled, while one-time pending support keeps the builder valid if the catalog grows.
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build(),
        )
        .enableAutoServiceReconnection()
        .build()

    override suspend fun refresh(): Result<PlayBillingSnapshot> = runCatching {
        val connected = ensureConnected()
        if (connected.responseCode != BillingResponseCode.OK) {
            throw PlayBillingException(failureFor(connected.responseCode))
        }

        val detailsResult = queryProduct()
        val details = detailsResult.second
        if (detailsResult.first.responseCode == BillingResponseCode.OK && details != null) {
            productDetails = details
            val baseOffer = selectBaseOffer(details)
            offerToken = baseOffer?.offerToken
        } else {
            productDetails = null
            offerToken = null
        }

        val purchasesResult = queryPurchases()
        if (purchasesResult.first.responseCode != BillingResponseCode.OK) {
            throw PlayBillingException(failureFor(purchasesResult.first.responseCode))
        }
        val purchases = purchasesResult.second
            .filter { productId in it.products }

        PlayBillingSnapshot(
            formattedPrice = selectedFormattedPrice(productDetails),
            purchases = purchases
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .map { PlayPurchase(it.purchaseToken, productId) },
            pending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING },
            productAvailable = productDetails != null && offerToken != null,
        )
    }.recoverCatching { failure ->
        if (failure is PlayBillingException) throw failure
        throw PlayBillingException(PlayBillingFailure.Unknown)
    }

    override fun launch(
        activity: Activity,
        obfuscatedAccountId: String,
    ): PlayBillingLaunch {
        val details = productDetails
            ?: return PlayBillingLaunch.Failed(PlayBillingFailure.ProductUnavailable)
        val token = offerToken
            ?: return PlayBillingLaunch.Failed(PlayBillingFailure.ProductUnavailable)

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(token)
            .build()
        val result = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                // A one-way account hash lets the backend reject a valid token copied from another
                // ViveNotes account. It is neither an email nor another piece of PII.
                .setObfuscatedAccountId(obfuscatedAccountId)
                .build(),
        )
        return when (result.responseCode) {
            BillingResponseCode.OK -> PlayBillingLaunch.Started
            BillingResponseCode.ITEM_ALREADY_OWNED -> PlayBillingLaunch.AlreadyOwned
            else -> PlayBillingLaunch.Failed(failureFor(result.responseCode))
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                val matching = purchases.orEmpty().filter { productId in it.products }
                mutableUpdates.tryEmit(
                    PlayBillingUpdate.Purchases(
                        completed = matching
                            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                            .map { PlayPurchase(it.purchaseToken, productId) },
                        pending = matching.any {
                            it.purchaseState == Purchase.PurchaseState.PENDING
                        },
                    ),
                )
            }

            BillingResponseCode.USER_CANCELED -> mutableUpdates.tryEmit(PlayBillingUpdate.Canceled)
            BillingResponseCode.ITEM_ALREADY_OWNED ->
                mutableUpdates.tryEmit(PlayBillingUpdate.Failed(PlayBillingFailure.AlreadyOwned))
            else -> mutableUpdates.tryEmit(
                PlayBillingUpdate.Failed(failureFor(result.responseCode)),
            )
        }
    }

    private suspend fun ensureConnected(): BillingResult = connectionMutex.withLock {
        if (client.isReady) return@withLock okBillingResult()

        suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result)
                }

                // Automatic service reconnection is enabled. The next query reconnects; manually
                // calling startConnection here would race that mechanism.
                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    private suspend fun queryProduct(): Pair<BillingResult, ProductDetails?> =
        suspendCancellableCoroutine { continuation ->
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build(),
                    ),
                )
                .build()
            client.queryProductDetailsAsync(params) { result, queryResult ->
                if (continuation.isActive) {
                    continuation.resume(result to queryResult.productDetailsList.firstOrNull())
                }
            }
        }

    private suspend fun queryPurchases(): Pair<BillingResult, List<Purchase>> =
        suspendCancellableCoroutine { continuation ->
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            client.queryPurchasesAsync(params) { result, purchases ->
                if (continuation.isActive) continuation.resume(result to purchases)
            }
        }

    private fun selectBaseOffer(details: ProductDetails): ProductDetails.SubscriptionOfferDetails? =
        details.subscriptionOfferDetails
            ?.firstOrNull { it.offerId == null }
            ?: details.subscriptionOfferDetails?.firstOrNull()

    private fun selectedFormattedPrice(details: ProductDetails?): String? {
        val offer = details?.subscriptionOfferDetails
            ?.firstOrNull { it.offerToken == offerToken }
            ?: return null
        // The final phase is the recurring base-plan price. An introductory phase may precede it,
        // but the stable monthly amount is what this one-plan screen promises.
        return offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice
    }

    private fun failureFor(code: Int): PlayBillingFailure = when (code) {
        BillingResponseCode.BILLING_UNAVAILABLE,
        BillingResponseCode.FEATURE_NOT_SUPPORTED,
        BillingResponseCode.SERVICE_DISCONNECTED,
        -> PlayBillingFailure.Unavailable

        BillingResponseCode.NETWORK_ERROR,
        BillingResponseCode.SERVICE_UNAVAILABLE,
        -> PlayBillingFailure.Network

        BillingResponseCode.DEVELOPER_ERROR -> PlayBillingFailure.DeveloperError
        BillingResponseCode.ITEM_UNAVAILABLE -> PlayBillingFailure.ProductUnavailable
        BillingResponseCode.ITEM_ALREADY_OWNED -> PlayBillingFailure.AlreadyOwned
        else -> PlayBillingFailure.Unknown
    }
}

internal class PlayBillingException(val reason: PlayBillingFailure) : Exception()

private fun okBillingResult(): BillingResult = BillingResult.newBuilder()
    .setResponseCode(BillingResponseCode.OK)
    .build()

/** Must remain byte-for-byte identical to the managed server's purchase binding check. */
internal fun obfuscatedPlayAccountId(accountId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("vivenotes:$accountId".encodeToByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
