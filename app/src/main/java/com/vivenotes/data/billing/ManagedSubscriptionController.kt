package com.vivenotes.data.billing

import android.app.Activity
import android.content.Context
import com.vivenotes.BuildConfig
import com.vivenotes.data.installedByGooglePlay
import com.vivenotes.data.sync.CouponGrant
import com.vivenotes.data.sync.ManagedSubscriptionStatus
import com.vivenotes.data.sync.PaidProvider
import com.vivenotes.data.sync.StripePlan
import com.vivenotes.data.sync.StripeRedirect
import com.vivenotes.data.sync.SubscriptionFailure
import com.vivenotes.data.sync.SubscriptionResult
import com.vivenotes.data.sync.SyncAccounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

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
    AlreadySubscribed,
    ServerBillingUnavailable,
    InvalidRequest,
    ServerUnreachable,

    /** A card checkout or portal request found no server; there is no Play purchase to restore. */
    CardServerUnreachable,

    /** No browser could open a Stripe page. */
    BrowserUnavailable,
    ServerError,
}

/** Who sells the plan on this install. */
enum class CheckoutProvider {
    GooglePlay,

    /**
     * Stripe Checkout in a browser. Only on a copy Google Play did not install, and only while the
     * managed server sells by card: Play's payments policy governs the copies Play delivers.
     */
    Stripe,
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
    /** A Play sheet or a Stripe page is being opened; its button shows progress. */
    val purchasing: Boolean = false,
    val redeemingCoupon: Boolean = false,
    val couponGrant: CouponGrant? = null,
    val purchaseConfirmed: Boolean = false,
    val failure: ManagedSubscriptionFailure? = null,
    val checkout: CheckoutProvider = CheckoutProvider.GooglePlay,
    /** Stripe's monthly price for this locale; null unless card checkout is offered. */
    val cardPrice: String? = null,
    /** Whether a card plan's Stripe portal can be opened from this install. */
    val cardManagementAvailable: Boolean = false,
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

    suspend fun stripePlan(account: ManagedBillingAccount): SubscriptionResult<StripePlan>

    suspend fun openStripeCheckout(account: ManagedBillingAccount): SubscriptionResult<StripeRedirect>

    suspend fun openStripePortal(account: ManagedBillingAccount): SubscriptionResult<StripeRedirect>

    suspend fun refreshStripe(account: ManagedBillingAccount): SubscriptionResult<ManagedSubscriptionStatus>
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

    override suspend fun stripePlan(account: ManagedBillingAccount): SubscriptionResult<StripePlan> =
        accounts.stripePlan(expectedAccountId = account.accountId)

    override suspend fun openStripeCheckout(
        account: ManagedBillingAccount,
    ): SubscriptionResult<StripeRedirect> = accounts.openStripeCheckout(expectedAccountId = account.accountId)

    override suspend fun openStripePortal(
        account: ManagedBillingAccount,
    ): SubscriptionResult<StripeRedirect> = accounts.openStripePortal(expectedAccountId = account.accountId)

    override suspend fun refreshStripe(
        account: ManagedBillingAccount,
    ): SubscriptionResult<ManagedSubscriptionStatus> =
        accounts.refreshStripeSubscriptions(expectedAccountId = account.accountId)
}

/**
 * Joins Play's purchase UI, or Stripe's hosted pages, to the managed server's entitlement.
 *
 * The distinction is load-bearing: a purchased token or a finished Stripe page is evidence for the
 * server, never a local permission bit. Only [ManagedSubscriptionStatus] can say storage is active.
 * The controller lives at application scope so a purchase-sheet result is still processed if the
 * Account destination was closed while Google Play was open.
 */
class ManagedSubscriptionController internal constructor(
    private val backend: ManagedSubscriptionBackend,
    private val play: PlayBillingStore,
    private val productId: String,
    private val scope: CoroutineScope,
    /** False on a copy Google Play installed: card checkout is never offered there. */
    private val cardCheckoutAllowed: Boolean = false,
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
        cardCheckoutAllowed = !installedByGooglePlay(context),
    )

    private val mutableState = MutableStateFlow(ManagedSubscriptionState())
    val state: StateFlow<ManagedSubscriptionState> = mutableState.asStateFlow()

    private val mutableBrowserPages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * Stripe pages for the UI to open in a Custom Tab. One-shot: a page nobody is collecting when it
     * is ready is dropped rather than opened later over whatever the person has moved on to.
     */
    val browserPages: Flow<String> = mutableBrowserPages.asSharedFlow()

    private val refreshMutex = Mutex()

    @Volatile
    private var currentAccount: ManagedBillingAccount? = null

    /** Set while a Stripe page is open, so the return to the app knows to read what happened. */
    @Volatile
    private var browserVisit: BrowserVisit? = null

    private enum class BrowserVisit { Checkout, Portal }

    init {
        scope.launch {
            backend.account.collectLatest { account ->
                currentAccount = account
                browserVisit = null
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

    /** Re-query both Play and the managed backend after an explicit billing action. */
    fun refresh() {
        val account = currentAccount ?: return
        scope.launch { refreshInternal(account) }
    }

    /** Subscribe, through whichever checkout this install offers. */
    fun purchase(activity: Activity) {
        if (mutableState.value.checkout == CheckoutProvider.Stripe) {
            subscribeByCard()
            return
        }
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

    /** Opens Stripe Checkout. [purchase] comes here on an install that sells by card. */
    internal fun subscribeByCard() {
        val account = currentAccount ?: return
        if (mutableState.value.purchasing) return
        mutableState.updateFor(account) {
            copy(purchasing = true, purchaseConfirmed = false, failure = null)
        }
        openBrowserPage(account, BrowserVisit.Checkout)
    }

    /**
     * Opens the Stripe customer portal for a card plan. A Play plan is managed on Play's own page,
     * which the UI links to directly.
     */
    fun manageCardSubscription() {
        val account = currentAccount ?: return
        if (mutableState.value.purchasing || !mutableState.value.cardManagementAvailable) return
        mutableState.updateFor(account) { copy(purchasing = true, purchaseConfirmed = false, failure = null) }
        openBrowserPage(account, BrowserVisit.Portal)
    }

    /**
     * The app is in the foreground again. If it had sent the person to a Stripe page, have the
     * server read what they did there — closing the tab is the only signal a Custom Tab gives.
     */
    fun onForeground() {
        val visit = browserVisit ?: return
        browserVisit = null
        val account = currentAccount ?: return
        scope.launch { refreshCard(account, afterCheckout = visit == BrowserVisit.Checkout) }
    }

    /** The UI could not open a page it was given. */
    fun onBrowserUnavailable() {
        val account = currentAccount ?: return
        browserVisit = null
        mutableState.updateFor(account) {
            copy(purchasing = false, failure = ManagedSubscriptionFailure.BrowserUnavailable)
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

    private fun openBrowserPage(account: ManagedBillingAccount, visit: BrowserVisit) {
        scope.launch {
            val opened = when (visit) {
                BrowserVisit.Checkout -> backend.openStripeCheckout(account)
                BrowserVisit.Portal -> backend.openStripePortal(account)
            }
            when (opened) {
                is SubscriptionResult.Success -> {
                    browserVisit = visit
                    mutableState.updateFor(account) { copy(purchasing = false) }
                    if (!mutableBrowserPages.tryEmit(opened.value.url)) browserVisit = null
                }

                SubscriptionResult.Unauthorized -> Unit // Account flow becomes null after revocation.
                is SubscriptionResult.Failed -> {
                    mutableState.updateFor(account) {
                        copy(purchasing = false, failure = opened.reason.toCardFailure())
                    }
                    // The server knows of a plan this screen does not show yet; show it.
                    if (opened.reason == SubscriptionFailure.AlreadySubscribed) {
                        refreshCard(account, afterCheckout = false)
                    }
                }
            }
        }
    }

    private suspend fun refreshCard(account: ManagedBillingAccount, afterCheckout: Boolean) {
        refreshMutex.withLock {
            if (currentAccount != account) return
            mutableState.updateFor(account) { copy(loading = true) }
            when (val result = backend.refreshStripe(account)) {
                is SubscriptionResult.Success -> {
                    val status = result.value
                    mutableState.updateFor(account) {
                        copy(
                            loading = false,
                            status = status,
                            purchaseConfirmed = afterCheckout && status.active &&
                                status.paidProvider == PaidProvider.Stripe,
                        )
                    }
                }

                SubscriptionResult.Unauthorized -> Unit
                is SubscriptionResult.Failed -> mutableState.updateFor(account) {
                    copy(loading = false, failure = failure ?: result.reason.toCardFailure())
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

            // Asked only where card checkout may be offered at all. A server without a Stripe
            // account answers BillingUnavailable, and this install then keeps Play's checkout.
            val cardPlan = if (cardCheckoutAllowed) {
                (backend.stripePlan(account) as? SubscriptionResult.Success)?.value
            } else {
                null
            }

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
                    checkout = if (cardPlan != null) CheckoutProvider.Stripe else CheckoutProvider.GooglePlay,
                    cardPrice = cardPlan?.let(::formattedCardPrice),
                    cardManagementAvailable = cardPlan != null,
                    failure = confirmationFailure
                        ?: (subscriptionResult as? SubscriptionResult.Failed)?.reason?.toManagedFailure()
                        // Play being unusable is not news on a copy that sells by card instead.
                        ?: playFailure?.takeIf { cardPlan == null },
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

/**
 * "$4.99" for 499 USD cents, in the device's locale. Null for anything but a monthly price: every
 * string around it says "per month".
 */
internal fun formattedCardPrice(plan: StripePlan, locale: Locale = Locale.getDefault()): String? {
    if (plan.interval != "month" || plan.intervalCount != 1L) return null
    val currency = runCatching { Currency.getInstance(plan.currency.uppercase(Locale.ROOT)) }.getOrNull()
        ?: return null
    val fractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
    val format = NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = currency
        minimumFractionDigits = fractionDigits
        maximumFractionDigits = fractionDigits
    }
    return format.format(BigDecimal.valueOf(plan.unitAmount).movePointLeft(fractionDigits))
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
    SubscriptionFailure.AlreadySubscribed -> ManagedSubscriptionFailure.AlreadySubscribed
    SubscriptionFailure.BillingUnavailable -> ManagedSubscriptionFailure.ServerBillingUnavailable
    SubscriptionFailure.InvalidRequest -> ManagedSubscriptionFailure.InvalidRequest
    SubscriptionFailure.Unreachable -> ManagedSubscriptionFailure.ServerUnreachable
    SubscriptionFailure.ServerError,
    SubscriptionFailure.NotAViveServer,
    SubscriptionFailure.NotConnected,
    SubscriptionFailure.NotManaged,
    -> ManagedSubscriptionFailure.ServerError
}

/** The same mapping, except that an unreachable server holds no Play purchase to reassure about. */
private fun SubscriptionFailure.toCardFailure(): ManagedSubscriptionFailure =
    if (this == SubscriptionFailure.Unreachable) {
        ManagedSubscriptionFailure.CardServerUnreachable
    } else {
        toManagedFailure()
    }
