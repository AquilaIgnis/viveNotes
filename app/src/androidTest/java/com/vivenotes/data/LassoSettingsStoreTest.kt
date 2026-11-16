package com.vivenotes.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LassoSettingsStoreTest {
    @Test fun lassoChoicesSurviveANewStoreWithoutChangingTheOtherTools() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PenSettingsStore(context)
        val previous = store.lasso.first()
        val eraser = store.eraser.first()
        val pens = store.pens.first()
        val settings = LassoSettings(ink = false, shapes = false, objects = false)
        try {
            store.setLasso(settings)
            assertEquals(settings, PenSettingsStore(context).lasso.first())
            assertEquals(eraser, store.eraser.first())
            assertEquals(pens, store.pens.first())
        } finally {
            store.setLasso(previous)
        }
    }
}
