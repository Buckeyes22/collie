package com.lateapex.collie.ui

import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PackActivityParityTest {
    private val resources = ApplicationProvider.getApplicationContext<CollieApplication>().resources

    @Test
    fun memberAndEmptyStateCopyMatchesTheWebRoute() {
        assertEquals("State", resources.getString(R.string.pack_member_health))
        assertEquals("Version", resources.getString(R.string.pack_member_version))
        assertEquals("Secret", resources.getString(R.string.pack_summary_secret))
        assertEquals(
            "workshop also leads · warrant 4",
            resources.getString(R.string.pack_conflict_value, "workshop", 4),
        )
        assertEquals(
            "Has not picked up the current secret.",
            resources.getString(R.string.pack_member_secret_behind),
        )
        assertEquals(
            "This collie is not leading a pack",
            resources.getString(R.string.pack_solo_title),
        )
        assertEquals(
            "The bridge did not answer. Collie tries again on the next poll.",
            resources.getString(R.string.pack_error_description),
        )
    }
}
