package com.vivenotes.data.billing

import android.app.Activity
import com.vivenotes.data.sync.CouponGrant
import com.vivenotes.data.sync.ManagedSubscriptionStatus
import com.vivenotes.data.sync.PaidSubscriptionState
import com.vivenotes.data.sync.SubscriptionFailure
import com.vivenotes.data.sync.SubscriptionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val confirmations = mutableListOf<Pair<String, String>>()
        val redemptions = mutableListOf<String>()

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

        fun inactiveStatus() = ManagedSubscriptionStatus(
            active = false,
            validUntil = null,
            promotionalValidUntil = null,
            paidValidUntil = null,
            paidState = null,
            autoRenewing = false,
            productId = null,
        )

        fun activeStatus() = ManagedSubscriptionStatus(
            active = true,
            validUntil = "2026-10-03T12:00:00Z",
            promotionalValidUntil = "2026-10-03T12:00:00Z",
            paidValidUntil = "2026-10-01T12:00:00Z",
            paidState = PaidSubscriptionState.Active,
            autoRenewing = true,
            productId = PRODUCT_ID,
        )
    }
}
