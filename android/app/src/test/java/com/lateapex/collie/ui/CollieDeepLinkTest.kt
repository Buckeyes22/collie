package com.lateapex.collie.ui

import android.net.Uri
import com.lateapex.collie.domain.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CollieDeepLinkTest {
    private val host = "ed8.taile7b6b1.ts.net"

    @Test
    fun parsesCanonicalPaneHistoryAndScope() {
        assertEquals(
            CollieDeepLink.Pane("w1:p2", Scope(host = "peer", session = "work"), history = true),
            CollieDeepLink.parse(Uri.parse("https://$host/pane/w1%3Ap2/history?h=peer&s=work"), host),
        )
    }

    @Test
    fun parsesWidenedHomeAndSettingsUpdates() {
        assertEquals(CollieDeepLink.Home(Scope(viewAll = true)), CollieDeepLink.parse(Uri.parse("https://$host/?all=1"), host))
        assertEquals(
            CollieDeepLink.Settings(updates = true, scope = Scope()),
            CollieDeepLink.parse(Uri.parse("https://$host/settings/updates"), host),
        )
    }

    @Test
    fun preservesSettingsPairEntryAndPairedDevicesAnchor() {
        assertEquals(
            CollieDeepLink.Settings(
                updates = false,
                scope = Scope(host = "peer"),
                pairCode = "AB12-CD34",
                focusDevices = true,
            ),
            CollieDeepLink.parse(
                Uri.parse("https://$host/settings?h=peer&pair=%20ab12-cd34%20#paired-devices"),
                host,
            ),
        )
    }

    @Test
    fun rejectsOtherOriginsAndUnknownRoutes() {
        assertNull(CollieDeepLink.parse(Uri.parse("https://example.com/pane/w1:p1"), host))
        assertNull(CollieDeepLink.parse(Uri.parse("https://$host/admin"), host))
    }
}
