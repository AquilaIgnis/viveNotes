package com.vivenotes.data.billing

import android.app.Activity
import android.content.Context
import com.vivenotes.BuildConfig
import com.vivenotes.data.sync.CouponGrant
import com.vivenotes.data.sync.ManagedSubscriptionStatus
import com.vivenotes.data.sync.SubscriptionFailure
import com.vivenotes.data.sync.SubscriptionResult
import com.vivenotes.data.sync.SyncAccounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ManagedBillingAccount(val accountId: String)

enum class ManagedSubscriptionFailure {
    PlayUnavailable,
    PlayNetwork,
    ProductUnavailable,
    PlayConfiguration,
    InvalidCoupon,
    CouponExpired,
    CouponAlreadyRedeemed,
    InvalidPurchase,
    PurchaseAlreadyClaimed,
    ServerBillingUnavailable,
    InvalidRequest,
    ServerUnreachable,
    ServerError,
}

data class ManagedSubscriptionState(
    /** False for disconnected and self-hosted accounts; the UI must omit the whole surface. */
    val visible: Boolean = false,
    val loading: Boolean = false,
    val status: ManagedSubscriptionStatus? = null,
    val formattedPrice: String? = null,
    val productAvailable: Boolean = false,
    val playPurchaseOwned: Boolean = false,
    val purchasePending: Boolean = false,
    val purchasing: Boolean = false,
    val redeemingCoupon: Boolean = false,
    val couponGrant: CouponGrant? = null,
    val purchaseConfirmed: Boolean = false,
    val failure: ManagedSubscriptionFailure? = null,
)

internal interface ManagedSubscriptionBackend {
    val account: Flow<ManagedBillingAccount?>

    suspend fun read(account: ManagedBillingAccount): SubscriptionResult<ManagedSubscriptionStatus>

    suspend fun confirm(
        account: ManagedBillingAccount,
        purchaseToken: String,
        productId: String,
    ): SubscriptionResult<ManagedSubscriptionStatus>

    suspend fun redeem(
        account: ManagedBillingAccount,
        code: String,
    ): SubscriptionResult<CouponGrant>
}

private class SyncManagedSubscriptionBackend(
    private val accounts: SyncAccounts,
) : ManagedSubscriptionBackend {
    override val account: Flow<ManagedBillingAccount?> = accounts.account
        .map { stored ->
            stored?.takeIf(accounts::isManagedAccount)?.let { ManagedBillingAccount(it.accountId) }
        }
        .distinctUntilChanged()

    override suspend fun read(account: ManagedBillingAccount): SubscriptionResult<ManagedSubscriptionStatus> =
        accounts.managedSubscription(expectedAccountId = account.accountId)

    override suspend fun confirm(
        account: ManagedBillingAccount,
        purchaseToken: String,
        productId: String,
    ): SubscriptionResult<ManagedSubscriptionStatus> =
        accounts.confirmGooglePlaySubscription(
            purchaseToken,
            productId,
            expectedAccountId = account.accountId,
        )

    override suspend fun redeem(
        account: ManagedBillingAccount,
        code: String,
    ): SubscriptionResult<CouponGrant> =
        accounts.redeemManagedCoupon(code, expectedAccountId = account.accountId)
}

/**
 * Joins Play's purchase UI to the managed server's entitlement.
 *
 * The distinction is load-bearing: a purchased token is evidence to send to the server, never a
 * local permission bit. Only [ManagedSubscriptionStatus] can say storage is active. The controller
 * lives at application scope so a purchase-sheet result is still processed if the Account
 * destination was closed while Google Play was open.
 */
class ManagedSubscriptionController internal constructor(
    private val backend: ManagedSubscriptionBackend,
    private val play: PlayBillingStore,
    private val productId: String,
    private val scope: CoroutineScope,
) {

    constructor(
        context: Context,
        accounts: SyncAccounts,
        scope: CoroutineScope,
    ) : this(
        backend = SyncManagedSubscriptionBackend(accounts),
        play = GooglePlayBillingStore(
            context = context,
            productId = BuildConfig.CLOUD_STORAGE_SUBSCRIPTION_ID,
        ),
        productId = BuildConfig.CLOUD_STORAGE_SUBSCRIPTION_ID,
        scope = scope,
    )

    private val mutableState = MutableStateFlow(ManagedSubscriptionState())
    val state: StateFlow<ManagedSubscriptionState> = mutableState.asStateFlow()

    private val refreshMutex = Mutex()

    @Volatile
    private var currentAccount: ManagedBillingAccount? = null

    init {
        scope.launch {
            backend.account.collectLatest { account ->
                currentAccount = account
                if (account == null) {
                    mutableState.value = ManagedSubscriptionState()
                } else {
                    mutableState.value = ManagedSubscriptionState(visible = true, loading = true)
                    refreshInternal(account)
                }
            }
        }
        scope.launch {
            play.updates.collect { update ->
                val account = currentAccount ?: return@collect
                when (update) {
                    PlayBillingUpdate.Canceled -> mutableState.updateFor(account) {
                        copy(purchasing = false, failure = null)
                    }

                    is PlayBillingUpdate.Failed -> {
                        if (update.reason == PlayBillingFailure.AlreadyOwned) {
                            refreshInternal(account)
                        } else {
                            mutableState.updateFor(account) {
                                copy(
                                    purchasing = false,
                                    failure = update.reason.toManagedFailure(),
                                )
                            }
                        }
                    }

                    is PlayBillingUpdate.Purchases -> {
                        if (update.pending && update.completed.isEmpty()) {
                            mutableState.updateFor(account) {
                                copy(
                                    purchasing = false,
                                    purchasePending = true,
                                    failure = null,
                                )
                            }
                        } else {
                            confirmPurchases(account, update.completed, update.pending)
                        }
                    }
                }
            }
        }
    }

    /** Re-query both Play and the managed backend; safe to call whenever Account opens. */
    fun refresh() {
        val account = currentAccount ?: return
        scope.launch { refreshInternal(account) }
    }

    fun purchase(activity: Activity) {
        val account = currentAccount ?: return
        if (mutableState.value.purchasing) return
        mutableState.updateFor(account) {
            copy(purchasing = true, purchaseConfirmed = false, failure = null)
        }
        when (val launched = play.launch(activity, obfuscatedPlayAccountId(account.accountId))) {
            PlayBillingLaunch.Started -> Unit
            PlayBillingLaunch.AlreadyOwned -> {
                mutableState.updateFor(account) { copy(purchasing = false) }
                refresh()
            }
            is PlayBillingLaunch.Failed -> mutableState.updateFor(account) {
                copy(
                    purchasing = false,
                    failure = launched.reason.toManagedFailure(),
                )
            }
        }
    }

    fun redeemCoupon(code: String) {
        val account = currentAccount ?: return
        if (code.isBlank() || mutableState.value.redeemingCoupon) return
        mutableState.updateFor(account) {
            copy(
                redeemingCoupon = true,
                couponGrant = null,
                purchaseConfirmed = false,
                failure = null,
            )
        }
        scope.launch {
            when (val result = backend.redeem(account, code)) {
                is SubscriptionResult.Success -> {
                    val status = backend.read(account)
                    mutableState.updateFor(account) {
                        copy(
                            redeemingCoupon = false,
                            couponGrant = result.value,
                            status = (status as? SubscriptionResult.Success)?.value ?: this.status,
                            failure = (status as? SubscriptionResult.Failed)?.reason?.toManagedFailure(),
                        )
                    }
                }

                SubscriptionResult.Unauthorized -> Unit // Account flow becomes null after revocation.
                is SubscriptionResult.Failed -> mutableState.updateFor(account) {
                    copy(
                        redeemingCoupon = false,
                        failure = result.reason.toManagedFailure(),
                    )
                }
            }
        }
    }

    private suspend fun refreshInternal(account: ManagedBillingAccount) {
        refreshMutex.withLock {
            if (currentAccount != account) return
            mutableState.updateFor(account) {
                copy(loading = true, purchaseConfirmed = false, failure = null)
            }

            val playResult = play.refresh()
            val snapshot = playResult.getOrNull()
            var subscriptionResult: SubscriptionResult<ManagedSubscriptionStatus>? = null
            var confirmationFailure: ManagedSubscriptionFailure? = null

            if (snapshot != null && snapshot.purchases.isNotEmpty()) {
                for (purchase in snapshot.purchases) {
                    when (val confirmed = backend.confirm(account, purchase.purchaseToken, purchase.productId)) {
                        is SubscriptionResult.Success -> subscriptionResult = confirmed
                        SubscriptionResult.Unauthorized -> return
                        is SubscriptionResult.Failed -> {
                            confirmationFailure = confirmed.reason.toManagedFailure()
                            break
                        }
                    }
                }
            }
            if (subscriptionResult == null) subscriptionResult = backend.read(account)

            val playFailure = (playResult.exceptionOrNull() as? PlayBillingException)
                ?.reason
                ?.toManagedFailure()
            mutableState.updateFor(account) {
                copy(
                    loading = false,
                    status = (subscriptionResult as? SubscriptionResult.Success)?.value ?: status,
                    formattedPrice = snapshot?.formattedPrice ?: formattedPrice,
                    productAvailable = snapshot?.productAvailable ?: false,
                    playPurchaseOwned = snapshot?.purchases?.isNotEmpty() == true,
                    purchasePending = snapshot?.pending == true,
                    purchasing = false,
                    failure = confirmationFailure
                        ?: (subscriptionResult as? SubscriptionResult.Failed)?.reason?.toManagedFailure()
                        ?: playFailure,
                )
            }
        }
    }

    private suspend fun confirmPurchases(
        account: ManagedBillingAccount,
        purchases: List<PlayPurchase>,
        pending: Boolean,
    ) {
        if (purchases.isEmpty()) {
            mutableState.updateFor(account) { copy(purchasing = false, purchasePending = pending) }
            return
        }
        var latest: ManagedSubscriptionStatus? = null
        for (purchase in purchases) {
            when (val result = backend.confirm(account, purchase.purchaseToken, purchase.productId)) {
                is SubscriptionResult.Success -> latest = result.value
                SubscriptionResult.Unauthorized -> return
                is SubscriptionResult.Failed -> {
                    mutableState.updateFor(account) {
                        copy(
                            purchasing = false,
                            failure = result.reason.toManagedFailure(),
                        )
                    }
                    return
                }
            }
        }
        mutableState.updateFor(account) {
            copy(
                status = latest ?: status,
                playPurchaseOwned = true,
                purchasePending = pending,
                purchasing = false,
                purchaseConfirmed = true,
                failure = null,
            )
        }
    }

    private inline fun MutableStateFlow<ManagedSubscriptionState>.updateFor(
        account: ManagedBillingAccount,
        transform: ManagedSubscriptionState.() -> ManagedSubscriptionState,
    ) {
        if (currentAccount == account) value = value.transform()
    }
}

private fun PlayBillingFailure.toManagedFailure(): ManagedSubscriptionFailure = when (this) {
    PlayBillingFailure.Unavailable -> ManagedSubscriptionFailure.PlayUnavailable
    PlayBillingFailure.Network -> ManagedSubscriptionFailure.PlayNetwork
    PlayBillingFailure.ProductUnavailable -> ManagedSubscriptionFailure.ProductUnavailable
    PlayBillingFailure.DeveloperError -> ManagedSubscriptionFailure.PlayConfiguration
    PlayBillingFailure.AlreadyOwned -> ManagedSubscriptionFailure.ProductUnavailable
    PlayBillingFailure.Unknown -> ManagedSubscriptionFailure.PlayUnavailable
}

private fun SubscriptionFailure.toManagedFailure(): ManagedSubscriptionFailure = when (this) {
    SubscriptionFailure.InvalidCoupon -> ManagedSubscriptionFailure.InvalidCoupon
    SubscriptionFailure.CouponExpired -> ManagedSubscriptionFailure.CouponExpired
    SubscriptionFailure.CouponAlreadyRedeemed -> ManagedSubscriptionFailure.CouponAlreadyRedeemed
    SubscriptionFailure.InvalidPurchase -> ManagedSubscriptionFailure.InvalidPurchase
    SubscriptionFailure.PurchaseAlreadyClaimed -> ManagedSubscriptionFailure.PurchaseAlreadyClaimed
    SubscriptionFailure.BillingUnavailable -> ManagedSubscriptionFailure.ServerBillingUnavailable
    SubscriptionFailure.InvalidRequest -> ManagedSubscriptionFailure.InvalidRequest
    SubscriptionFailure.Unreachable -> ManagedSubscriptionFailure.ServerUnreachable
    SubscriptionFailure.ServerError,
    SubscriptionFailure.NotAViveServer,
    SubscriptionFailure.NotConnected,
    SubscriptionFailure.NotManaged,
    -> ManagedSubscriptionFailure.ServerError
}
