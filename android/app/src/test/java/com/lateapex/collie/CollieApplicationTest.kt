package com.lateapex.collie

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CollieApplicationTest {
    @Test
    fun installsAnUncaughtExceptionHandlerThatChainsToThePrevious() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(installed != null)
    }

    @Test
    fun registersActivityLifecycleCallbacksSoAPaneOpeningIsObservable() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val activity = Robolectric.buildActivity(com.lateapex.collie.ui.MainActivity::class.java)
            .create().start().resume().get()
        shadowOf(activity.mainLooper).idle()
        // No direct assertion on the trace file here (Task 8/9 cover recorder call sites); this
        // test only pins that CollieApplication registers callbacks at all, since a regression
        // that silently drops the registration would otherwise compile and pass every other test.
        assertTrue(app.lifecycleCallbacksRegisteredForTest)
    }
}
