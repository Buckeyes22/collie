package com.lateapex.collie.ui

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.EncryptedConnectionStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLaunchSmokeTest {
    @Test
    fun launcherShowsNativeConnectionSurfaceWithoutWebContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        (context.applicationContext as CollieApplication).container.connectionStore.clear()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(MainActivity::class.java.name, activity.intent.component?.className)
            }
            onView(withId(R.id.setup_panel)).check(matches(isDisplayed()))
            onView(withId(R.id.origin_input)).check(matches(withText(BuildConfig.DEFAULT_ORIGIN)))
            onView(isAssignableFrom(WebView::class.java)).check(doesNotExist())
        }
    }
}
