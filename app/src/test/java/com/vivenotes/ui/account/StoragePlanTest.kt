package com.vivenotes.ui.account

import com.vivenotes.data.billing.ManagedSubscriptionFailure
import com.vivenotes.data.billing.ManagedSubscriptionState
import com.vivenotes.data.sync.ManagedSubscriptionStatus
import com.vivenotes.data.sync.PaidSubscriptionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which layout the managed storage card picks. The card offers a purchase only for
 * [StoragePlan.NotSubscribed] and [StoragePlan.Coupon], so every mapping here decides whether a
 * paying person is shown a checkout.
 */
class StoragePlanTest {

    @Test
    fun paidAndRenewingIsTheOnlyPlanThatSaysRenews() {
        assertEquals(StoragePlan.Renewing, planFor(PaidSubscriptionState.Active, autoRenewing = true))
        assertEquals(
            StoragePlan.EndingWithoutRenewal,
            planFor(PaidSubscriptionState.Active, autoRenewing = false),
        )
    }

    @Test
    fun aCanceledPlanStillInItsPeriodEndsRatherThanAskingToBuy() {
        assertEquals(
            StoragePlan.EndingWithoutRenewal,
            planFor(PaidSubscriptionState.Canceled, active = true),
        )
        assertEquals(
            StoragePlan.NotSubscribed,
            planFor(PaidSubscriptionState.Canceled, active = false),
        )
    }

    @Test
    fun paymentTroubleIsItsOwnStateNotAnOfferToBuy() {
        assertEquals(StoragePlan.PaymentRetrying, planFor(PaidSubscriptionState.Grace))
        assertEquals(StoragePlan.OnHold, planFor(PaidSubscriptionState.OnHold, active = false))
        assertEquals(StoragePlan.Paused, planFor(PaidSubscriptionState.Paused, active = false))
        assertEquals(StoragePlan.PaymentPending, planFor(PaidSubscriptionState.Pending, active = false))
    }

    @Test
    fun couponAccessWithoutAPlayPlanIsCouponEvenAfterAnExpiredOne() {
        assertEquals(StoragePlan.Coupon, planFor(null))
        assertEquals(StoragePlan.Coupon, planFor(PaidSubscriptionState.Expired))
        assertEquals(StoragePlan.NotSubscribed, planFor(PaidSubscriptionState.Expired, active = false))
    }

    @Test
    fun aPendingPlayPurchaseWithNoAccessIsPending() {
        val state = ManagedSubscriptionState(
            visible = true,
            status = status(paidState = null, active = false),
            purchasePending = true,
        )

        assertEquals(StoragePlan.PaymentPending, storagePlan(state))
    }

    /** A status nobody has read yet, or could not read, is not a "no". */
    @Test
    fun anUnreadStatusNeverFallsThroughToNotSubscribed() {
        assertEquals(
            StoragePlan.Checking,
            storagePlan(ManagedSubscriptionState(visible = true, loading = true)),
        )
        assertEquals(
            StoragePlan.Unknown,
            storagePlan(
                ManagedSubscriptionState(
                    visible = true,
                    failure = ManagedSubscriptionFailure.ServerUnreachable,
                ),
            ),
        )
    }

    private fun planFor(
        paidState: PaidSubscriptionState?,
        active: Boolean = true,
        autoRenewing: Boolean = false,
    ): StoragePlan = storagePlan(
        ManagedSubscriptionState(
            visible = true,
            status = status(paidState, active, autoRenewing),
        ),
    )

    private fun status(
        paidState: PaidSubscriptionState?,
        active: Boolean,
        autoRenewing: Boolean = false,
    ) = ManagedSubscriptionStatus(
        active = active,
        validUntil = "2027-01-21T12:00:00Z",
        paidState = paidState,
        paidValidUntil = "2027-01-21T12:00:00Z",
        promotionalValidUntil = null,
        autoRenewing = autoRenewing,
        productId = "vivenotes_storage_monthly",
    )
}
