package com.lateapex.collie.ui

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ProxyCallbackActivityTest {
    @Test fun manifestResolvesTheFixedAuthorityFreeCallbackToTheExportedDispatcher() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val callback = Intent(Intent.ACTION_VIEW, Uri.parse("com.lateapex.collie:/oauth2redirect?state=fixture-state&code=private-code"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
        val resolved = context.packageManager.resolveActivity(callback, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull("Registered fixed callback must resolve through the actual manifest", resolved)
        assertEquals(OpenLinkActivity::class.java.name, resolved!!.activityInfo.name)
        assertTrue(resolved.activityInfo.exported)
    }

    @Test fun fixedCallbackRoutesOnlyAnInternalOneShotIdAndDestroysExternalIntentData() {
        val raw="com.lateapex.collie:/oauth2redirect?state=fixture-state&code=private-code&iss=https%3A%2F%2Fissuer.example%2F"
        val input=Intent(Intent.ACTION_VIEW,Uri.parse(raw))
        val controller=Robolectric.buildActivity(OpenLinkActivity::class.java,input).create()
        val activity=controller.get();val next=shadowOf(activity).nextStartedActivity
        assertNotNull(next);assertEquals(ProxySignInActivity::class.java.name,next.component!!.className);assertNull(next.data);assertNull(activity.intent.data)
        assertEquals(setOf(ProxySignInActivity.EXTRA_CALLBACK_ID),next.extras!!.keySet())
        val id=next.getStringExtra(ProxySignInActivity.EXTRA_CALLBACK_ID)!!
        assertFalse(id.contains("private-code"));assertEquals(raw,ProxyCallbackHandoff.consume(id));assertNull(ProxyCallbackHandoff.consume(id));assertTrue(activity.isFinishing)
        controller.destroy()
    }
    @Test fun foreignAuthorityPathFragmentActionAndArbitraryHttpLinksNeverReachSignIn() {
        for(raw in listOf("com.lateapex.collie://evil/oauth2redirect?code=x","com.lateapex.collie:/other?code=x","com.lateapex.collie:/oauth2redirect?code=x#fragment","https://issuer.example/oauth2redirect?code=x")) {
            val controller=Robolectric.buildActivity(OpenLinkActivity::class.java,Intent(Intent.ACTION_VIEW,Uri.parse(raw))).create()
            assertNull(shadowOf(controller.get()).nextStartedActivity);assertNull(controller.get().intent.data);controller.destroy()
        }
        val controller=Robolectric.buildActivity(OpenLinkActivity::class.java,Intent(Intent.ACTION_SEND,Uri.parse("com.lateapex.collie:/oauth2redirect?code=x"))).create()
        assertNull(shadowOf(controller.get()).nextStartedActivity);controller.destroy()
    }
}
