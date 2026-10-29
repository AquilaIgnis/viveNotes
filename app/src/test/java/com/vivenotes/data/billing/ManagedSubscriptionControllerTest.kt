package com.vivenotes.data.billing

import android.app.Activity
import com.vivenotes.data.sync.CouponGrant
import com.vivenotes.data.sync.ManagedSubscriptionStatus
import com.vivenotes.data.sync.PaidProvider
import com.vivenotes.data.sync.PaidSubscriptionState
import com.vivenotes.data.sync.StripePlan
import com.vivenotes.data.sync.StripeRedirect
import com.vivenotes.data.sync.SubscriptionFailure
import com.vivenotes.data.sync.SubscriptionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedSubscriptionControllerTest {

    @Test
    fun `managed account restores and confirms its Play purchase before showing access`() = runTest {
        val backend = FakeBackend(ManagedBillingAccount(ACCOUNT_ID)).apply {
            confirmed = SubscriptionResult.Success(activeStatus())
        }
        val play = FakePlayStore().apply {
            snapshot = Result.success(
                PlayBillingSnapshot(
                    formattedPrice = "\$4.99",
                    purchases = listOf(PlayPurchase("play-token", PRODUCT_ID)),
                    pending = false,
                    productAvailable = true,
                ),
            )
        }

        val controller = ManagedSubscriptionController(backend, play, PRODUCT_ID, backgroundScope)
        runCurrent()

        assertEquals(listOf("play-token" to PRODUCT_ID), backend.confirmations)
        assertTrue(controller.state.value.visible)
        assertTrue(controller.state.value.status?.active == true)
        assertTrue(controller.state.value.playPurchaseOwned)
        assertEquals("\$4.99", controller.state.value.formattedPrice)
        assertFalse(controller.state.value.loading)
    }

    @Test
    fun `disconnected and self-hosted accounts have no billing surface or Play query`() = runTest {
        val backend = FakeBackend(null)
        val play = FakePlayStore()
        val controller = ManagedSubscriptionController(backend, play, PRODUCT_ID, backgroundScope)
        runCurrent()

        assertFalse(controller.state.value.visible)
        assertEquals(0, play.refreshes)

        backend.accounts.value = ManagedBillingAccount(ACCOUNT_ID)
        runCurrent()
        assertTrue(controller.state.value.visible)
        assertEquals(1, play.refreshes)

        backend.accounts.value = null
        runCurrent()
        assertEquals(ManagedSubscriptionState(), controller.state.value)
    }

    @Test
    fun `coupon extends promotion while paid renewal stays in the returned status`() = runTest {
        val grant = CouponGrant(
            code = "FREE-MONTH",
            monthsGranted = 1,
            validUntil = "2026-10-03T12:00:00Z",
        )
        val backend = FakeBackend(ManagedBillingAccount(ACCOUNT_ID)).apply {
            readResult = SubscriptionResult.Success(activeStatus())
            redeemResult = SubscriptionResult.Success(grant)
        }
        val controller = ManagedSubscriptionController(
            backend,
            FakePlayStore(),
            PRODUCT_ID,
            backgroundScope,
        )
        runCurrent()

        controller.redeemCoupon(" free-month ")
        runCurrent()

        assertEquals(listOf(" free-month "), backend.redemptions)
        assertEquals(grant, controller.state.value.couponGrant)
        assertEquals(PaidSubscriptionState.Active, controller.state.value.status?.paidState)
        assertTrue(controller.state.value.status?.autoRenewing == true)
        assertFalse(controller.state.value.redeemingCoupon)
    }

    @Test
    fun `completed purchase update is sent to the backend and never grants locally by itself`() = runTest {
        val backend = FakeBackend(ManagedBillingAccount(ACCOUNT_ID)).apply {
            confirmed = SubscriptionResult.Failed(SubscriptionFailure.BillingUnavailable)
        }
        val play = FakePlayStore()
        val controller = ManagedSubscriptionController(backend, play, PRODUCT_ID, backgroundScope)
        runCurrent()

        play.events.emit(
            PlayBillingUpdate.Purchases(
                completed = listOf(PlayPurchase("play-token", PRODUCT_ID)),
                pending = false,
            ),
        )
        runCurrent()

        assertFalse(controller.state.value.playPurchaseOwned)
        assertFalse(controller.state.value.status?.active == true)
        assertEquals(
            ManagedSubscriptionFailure.ServerBillingUnavailable,
            controller.state.value.failure,
        )
    }

    @Test
    fun `an install Play did not make sells by card while the server does`() = runTest {
        val backend = FakeBackend(ManagedBillingAccount(ACCOUNT_ID)).apply {
            planResult = SubscriptionResult.Success(MONTHLY_PLAN)
        }
        val controller = cardController(backend)
        runCurrent()

        assertEquals(CheckoutProvider.Stripe, controller.state.value.checkout)
        assertTrue(controller.state.value.cardManagementAvailable)
        assertEquals(formattedCardPrice(MONTHLY_PLAN), controller.state.value.cardPrice)
    }

    @Test
    fun `a Play install never asks about card checkout`() = runTest {
        val backend = FakeBackend(ManagedBillingAccount(ACCOUNT_ID)).apply {
            planResult = SubscriptionResult.Success(MONTHLY_PLAN)
        }
        val controller = ManagedSubscriptionController(
            backend,
            FakePlayStore(),
            PRODUCT_ID,
            backgroundScope,
            cardCheckoutAllowed = false,
        )
        runCurrent()

        assertEquals(0, backend.planReads)
        assertEquals(CheckoutProvider.GooglePlay, controller.state.value.checkout)
        assertFalse(controller.state.value.cardManagementAvailable)

        controller.manageCardSubscription()
        runCurrent()
        assertEquals(0, backend.portalsOpened)
    }

    @Test
    fun `a server without a Stripe account leaves the install on Play checkout`() = runTest {
        val backend = FakeBackend(ManagedBillingAccount(ACCOUNT_ID))
        val controller = cardController(backend)
        runCurrent()

        assertEquals(1, backend.planReads)
        assertEquals(CheckoutProvider.GooglePlay, controller.state.value.checkout)
        assertNull(controller.state.value.cardPrice)
        assertNull(controller.state.value.failure)
    }

    @Test
    fun `card checkout opens Stripe and reads the plan back when the app returns`() = runTest {
        val backend = FakeBackend(ManagedBillingAccount(ACCOUNT_ID)).apply {
            planResult = SubscriptionResult.Success(MONTHLY_PLAN)
            refreshResult = SubscriptionResult.Success(activeStatus(PaidProvider.Stripe))
        }
        val controller = cardController(backend)
        val pages = collectPages(controller)
        runCurrent()

        controller.subscribeByCard()
        runCurrent()

        assertEquals(listOf(CHECKOUT_URL), pages)
        assertFalse(controller.state.value.purchasing)
        assertEquals(0, backend.stripeRefreshes)

        // Closing the Custom Tab brings the app back; that is when the server is asked.
        controller.onForeground()
        runCurrent()
        assertEquals(1, backend.stripeRefreshes)
        assertTrue(controller.state.value.purchaseConfirmed)
        assertEquals(PaidProvider.Stripe, controller.state.value.status?.paidProvider)

        controller.onForeground()
        runCurrent()
        assertEquals(1, backend.stripeRefreshes)
    }

    @Test
    fun `a refused second card plan shows the plan the server already has`() = runTest {
        val backend = FakeBackend(ManagedBillingAccount(ACCOUNT_ID)).apply {
            planResult = SubscriptionResult.Success(MONTHLY_PLAN)
            checkoutResult = SubscriptionResult.Failed(SubscriptionFailure.AlreadySubscribed)
            refreshResult = SubscriptionResult.Success(activeStatus(PaidProvider.Stripe))
        }
        val controller = cardController(backend)
        val pages = collectPages(controller)
        runCurrent()

        controller.subscribeByCard()
        runCurrent()

        assertTrue(pages.isEmpty())
        assertEquals(ManagedSubscriptionFailure.AlreadySubscribed, controller.state.value.failure)
        assertEquals(PaidProvider.Stripe, controller.state.value.status?.paidProvider)
        assertFalse(controller.state.value.purchaseConfirmed)
    }

    @Test
    fun `an unreachable server during card checkout says nothing about a Play purchase`() = runTest {
        val backend = FakeBackend(ManagedBillingAccount(ACCOUNT_ID)).apply {
            planResult = SubscriptionResult.Success(MONTHLY_PLAN)
            checkoutResult = SubscriptionResult.Failed(SubscriptionFailure.Unreachable)
        }
        val controller = cardController(backend)
        runCurrent()

        controller.subscribeByCard()
        runCurrent()

        assertEquals(ManagedSubscriptionFailure.CardServerUnreachable, controller.state.value.failure)
        assertFalse(controller.state.value.purchasing)
    }

    @Test
    fun `the portal is opened for a card plan and returning refreshes without confirming a purchase`() = runTest {
        val backend = FakeBackend(ManagedBillingAccount(ACCOUNT_ID)).apply {
            planResult = SubscriptionResult.Success(MONTHLY_PLAN)
            readResult = SubscriptionResult.Success(activeStatus(PaidProvider.Stripe))
            refreshResult = SubscriptionResult.Success(activeStatus(PaidProvider.Stripe))
        }
        val controller = cardController(backend)
        val pages = collectPages(controller)
        runCurrent()

        controller.manageCardSubscription()
        runCurrent()
        assertEquals(listOf(PORTAL_URL), pages)

        controller.onForeground()
        runCurrent()
        assertEquals(1, backend.stripeRefreshes)
        assertFalse(controller.state.value.purchaseConfirmed)
    }

    @Test
    fun `a page nothing could open is reported and not refreshed for`() = runTest {
        val backend = FakeBackend(ManagedBillingAccount(ACCOUNT_ID)).apply {
            planResult = SubscriptionResult.Success(MONTHLY_PLAN)
        }
        val controller = cardController(backend)
        collectPages(controller)
        runCurrent()

        controller.subscribeByCard()
        runCurrent()
        controller.onBrowserUnavailable()
        controller.onForeground()
        runCurrent()

        assertEquals(ManagedSubscriptionFailure.BrowserUnavailable, controller.state.value.failure)
        assertEquals(0, backend.stripeRefreshes)
    }

    @Test
    fun `card prices follow the locale and are shown only when monthly`() {
        assertEquals("\$4.99", formattedCardPrice(MONTHLY_PLAN, Locale.US))
        assertEquals("¥500", formattedCardPrice(StripePlan(500, "jpy", "month", 1), Locale.US))
        assertNull(formattedCardPrice(StripePlan(4_999, "usd", "year", 1), Locale.US))
        assertNull(formattedCardPrice(StripePlan(999, "usd", "month", 2), Locale.US))
        assertNull(formattedCardPrice(StripePlan(499, "zzz", "month", 1), Locale.US))
    }

    @Test
    fun `obfuscated account id matches the backend protocol vector`() {
        assertEquals(
            "09m8JSDxhh92wmL0BceRBSnvLXdKM3ND-JcAJ-mxN6Q",
            obfuscatedPlayAccountId(ACCOUNT_ID),
        )
    }

    private class FakeBackend(initial: ManagedBillingAccount?) : ManagedSubscriptionBackend {
        val accounts = MutableStateFlow(initial)
        override val account: Flow<ManagedBillingAccount?> = accounts
        var readResult: SubscriptionResult<ManagedSubscriptionStatus> =
            SubscriptionResult.Success(inactiveStatus())
        var confirmed: SubscriptionResult<ManagedSubscriptionStatus> =
            SubscriptionResult.Success(inactiveStatus())
        var redeemResult: SubscriptionResult<CouponGrant> =
            SubscriptionResult.Failed(SubscriptionFailure.InvalidCoupon)
        var planResult: SubscriptionResult<StripePlan> =
            SubscriptionResult.Failed(SubscriptionFailure.BillingUnavailable)
        var checkoutResult: SubscriptionResult<StripeRedirect> =
            SubscriptionResult.Success(StripeRedirect(CHECKOUT_URL, "2026-10-19T19:00:00Z"))
        var refreshResult: SubscriptionResult<ManagedSubscriptionStatus> =
            SubscriptionResult.Success(inactiveStatus())
        val confirmations = mutableListOf<Pair<String, String>>()
        val redemptions = mutableListOf<String>()
        var planReads = 0
        var portalsOpened = 0
        var stripeRefreshes = 0

        override suspend fun read(
            account: ManagedBillingAccount,
        ): SubscriptionResult<ManagedSubscriptionStatus> = readResult

        override suspend fun confirm(
            account: ManagedBillingAccount,
            purchaseToken: String,
            productId: String,
        ): SubscriptionResult<ManagedSubscriptionStatus> {
            confirmations += purchaseToken to productId
            return confirmed
        }

        override suspend fun redeem(
            account: ManagedBillingAccount,
            code: String,
        ): SubscriptionResult<CouponGrant> {
            redemptions += code
            return redeemResult
        }

        override suspend fun stripePlan(account: ManagedBillingAccount): SubscriptionResult<StripePlan> {
            planReads++
            return planResult
        }

        override suspend fun openStripeCheckout(
            account: ManagedBillingAccount,
        ): SubscriptionResult<StripeRedirect> = checkoutResult

        override suspend fun openStripePortal(
            account: ManagedBillingAccount,
        ): SubscriptionResult<StripeRedirect> {
            portalsOpened++
            return SubscriptionResult.Success(StripeRedirect(PORTAL_URL, null))
        }

        override suspend fun refreshStripe(
            account: ManagedBillingAccount,
        ): SubscriptionResult<ManagedSubscriptionStatus> {
            stripeRefreshes++
            return refreshResult
        }
    }

    private fun TestScope.cardController(backend: FakeBackend) = ManagedSubscriptionController(
        backend,
        FakePlayStore(),
        PRODUCT_ID,
        backgroundScope,
        cardCheckoutAllowed = true,
    )

    /** What the UI would open in a Custom Tab; collecting before the press, as NotesApp does. */
    private fun TestScope.collectPages(controller: ManagedSubscriptionController): List<String> {
        val pages = mutableListOf<String>()
        backgroundScope.launch { controller.browserPages.collect { pages += it } }
        return pages
    }

    private class FakePlayStore : PlayBillingStore {
        val events = MutableSharedFlow<PlayBillingUpdate>(extraBufferCapacity = 4)
        override val updates: Flow<PlayBillingUpdate> = events
        var snapshot: Result<PlayBillingSnapshot> = Result.success(
            PlayBillingSnapshot(
                formattedPrice = null,
                purchases = emptyList(),
                pending = false,
                productAvailable = false,
            ),
        )
        var refreshes = 0

        override suspend fun refresh(): Result<PlayBillingSnapshot> {
            refreshes++
            return snapshot
        }

        override fun launch(
            activity: Activity,
            obfuscatedAccountId: String,
        ): PlayBillingLaunch = PlayBillingLaunch.Started
    }

    private companion object {
        const val ACCOUNT_ID = "10000000-0000-0000-0000-000000000001"
        const val PRODUCT_ID = "vivenotes_storage_monthly"
        const val CHECKOUT_URL = "https://checkout.stripe.com/c/pay/cs_test_1"
        const val PORTAL_URL = "https://billing.stripe.com/p/session/test_1"
        val MONTHLY_PLAN = StripePlan(unitAmount = 499, currency = "usd", interval = "month", intervalCount = 1)

        fun inactiveStatus() = ManagedSubscriptionStatus(
            active = false,
            validUntil = null,
            promotionalValidUntil = null,
            paidValidUntil = null,
            paidState = null,
            autoRenewing = false,
            productId = null,
        )

        fun activeStatus(provider: PaidProvider = PaidProvider.GooglePlay) = ManagedSubscriptionStatus(
            active = true,
            validUntil = "2026-10-03T12:00:00Z",
            promotionalValidUntil = "2026-10-03T12:00:00Z",
            paidValidUntil = "2026-10-01T12:00:00Z",
            paidState = PaidSubscriptionState.Active,
            autoRenewing = true,
            productId = PRODUCT_ID,
            paidProvider = provider,
        )
    }
}
