package com.lateapex.collie.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.custodyVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Internal native profile review and external ACTION_VIEW sign-in. No cookie/browser runtime enters the app. */
class ProxySignInActivity : AppCompatActivity() {
    private val container get() = (application as CollieApplication).container
    private lateinit var status: TextView
    private lateinit var profile: EditText
    private lateinit var signIn: Button
    private lateinit var import: Button
    private var retirement: AutoCloseable? = null
    private var review: androidx.appcompat.app.AlertDialog? = null
    private var work: kotlinx.coroutines.Job? = null
    private class ProfileReview(val actor: com.lateapex.collie.data.ScopedReadContext, val version: Long, val raw: CharArray)
    private val custodyLock = Any()
    private var reviewed: ProfileReview? = null
    private var callback: CharArray? = null
    private var callbackActor: ScopedReadContext? = null
    @Volatile private var presentationActor: ScopedReadContext? = null
    @Volatile private var started = false
    private val actorVersion = java.util.concurrent.atomic.AtomicLong()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presentationActor = container.repository.readContext()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { text = getString(R.string.proxy_identity_explanation); isFocusable = true; accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        profile = EditText(this).apply { hint = getString(R.string.proxy_identity_profile); minLines = 4; maxLines = 10; isSaveEnabled = false; filters = arrayOf(android.text.InputFilter.LengthFilter(65_536)); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        import = Button(this).apply { text = getString(R.string.proxy_identity_import); minHeight = dp(48) }
        signIn = Button(this).apply { text = getString(R.string.proxy_identity_sign_in); minHeight = dp(48) }
        val copyKey = Button(this).apply { text = getString(R.string.proxy_identity_copy_key); minHeight = dp(48) }
        copyKey.setOnClickListener {
            if (!started) return@setOnClickListener
            val actor = container.repository.readContext() ?: return@setOnClickListener
            adoptActor(actor)
            runCatching { container.proxyCoordinator.deviceKeyThumbprint(actor) }.onSuccess { key ->
                if (container.repository.isCurrent(actor)) {
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.proxy_identity_key_label), key))
                    status.text = getString(R.string.proxy_identity_key_copied, actor.connection.label)
                }
            }.onFailure { status.setText(R.string.proxy_identity_refused) }
        }
        column.addView(copyKey)
        column.addView(status); column.addView(profile); column.addView(import); column.addView(signIn)
        setContentView(ScrollView(this).apply { addView(column) })
        retirement = container.repository.onReadContextRetired { event ->
            // Compare the COPY owner again on Main, after a possible fresh B import/refresh.
            // Cancellation and ACK remain outside repository/store/private custody locks.
            if (retirePresentation(event)) {
                withRetirementOnMain(event, { presentationActor }) {
                    wipeOnMainAndAwait { status.setText(R.string.proxy_identity_actor_changed) }
                }
            }
        }
        import.setOnClickListener {
            val actor = container.repository.readContext() ?: return@setOnClickListener
            if (!started) return@setOnClickListener
            adoptActor(actor)
            val raw = profile.text.toString().toCharArray()
            val parsed = runCatching { com.lateapex.collie.auth.ProxyProfile.parse(String(raw)) }.getOrNull()
            if (parsed == null) { raw.fill('\u0000'); status.setText(R.string.proxy_identity_invalid); return@setOnClickListener }
            review?.dismiss()
            val candidate = ProfileReview(actor, actorVersion.get(), raw)
            synchronized(custodyLock) { reviewed?.raw?.fill('\u0000'); reviewed = candidate }
            profile.text.clear()
            review = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.proxy_identity_review)
                .setMessage(getString(R.string.proxy_identity_review_details, actor.key.origin, parsed.issuer, parsed.clientId) + "\n" +
                    getString(R.string.proxy_identity_review_scopes, parsed.authorizationScope))
                .setPositiveButton(R.string.proxy_identity_import) { _, _ ->
                    val owned = synchronized(custodyLock) {
                        if (!started || actorVersion.get() != candidate.version || reviewed !== candidate) null
                        else String(candidate.raw).also { candidate.raw.fill('\u0000'); reviewed = null }
                    } ?: return@setPositiveButton
                    if (!container.repository.isCurrent(actor)) return@setPositiveButton
                    runCatching { container.proxyCoordinator.importProfile(owned, actor) }.onSuccess { profile.text.clear(); container.repository.proxyIdentityChanged(); status.setText(R.string.proxy_identity_profile_ready) }
                        .onFailure { status.setText(R.string.proxy_identity_invalid) }
                }.setNegativeButton(android.R.string.cancel, null).create().also { dialog ->
                    dialog.setOnDismissListener { synchronized(custodyLock) { if (reviewed === candidate) { candidate.raw.fill('\u0000'); reviewed = null } } }
                    dialog.show()
                }
        }
        signIn.setOnClickListener { runIdentity { actor, admitted -> container.proxyCoordinator.begin(actor, admitted) } }
        intent.getStringExtra(EXTRA_CALLBACK_ID)?.let { id ->
            ProxyCallbackHandoff.consumeOwned(id)?.let { owned ->
                callback = owned.callback.toCharArray()
                callbackActor = owned.actor ?: presentationActor
            }
        }
    }
    private fun adoptActor(actor: ScopedReadContext) {
        if (presentationActor?.custodyVersion?.let { it != actor.custodyVersion } == true) {
            retirePresentation()
            wipeOnMainAndAwait()
        }
        synchronized(custodyLock) { presentationActor = actor }
    }
    private fun runIdentity(expectedActor: ScopedReadContext? = null, action: (com.lateapex.collie.data.ScopedReadContext, () -> Boolean) -> String) {
        if (!started) return
        val actor = expectedActor ?: container.repository.readContext() ?: run { status.setText(R.string.proxy_identity_not_connected); return }
        if (!container.repository.isCurrent(actor)) { status.setText(R.string.proxy_identity_actor_changed); return }
        adoptActor(actor)
        if (work?.isActive == true) return
        val version = actorVersion.get()
        val admitted = { started && version == actorVersion.get() && container.repository.isCurrent(actor) }
        signIn.isEnabled = false; import.isEnabled = false; status.setText(R.string.proxy_identity_waiting)
        work = lifecycleScope.launch {
            val answer = withContext(Dispatchers.IO) { runCatching { check(admitted()); action(actor, admitted) } }
            if (!admitted()) return@launch
            work = null
            signIn.isEnabled = true; import.isEnabled = true
            answer.onSuccess { result ->
                when (result) {
                    "completed" -> { container.repository.proxyIdentityChanged(); status.setText(R.string.proxy_identity_complete) }
                    "refused" -> status.setText(R.string.proxy_identity_refused)
                    else -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result)))
                }
            }.onFailure { status.setText(R.string.proxy_identity_refused) }
        }
    }
    override fun onStart() {
        super.onStart(); started = true
        val owned = synchronized(custodyLock) { callback?.let { it to callbackActor }.also { callback = null; callbackActor = null } }
        if (owned != null) {
            val (raw, original) = owned
            val text = String(raw); raw.fill('\u0000')
            runIdentity(original) { actor, admitted -> if (container.proxyCoordinator.consumeCallback(text, actor, admitted)) "completed" else "refused" }
        }
    }
    private fun retirePresentation(event: com.lateapex.collie.data.AuthorityRetirement? = null): Boolean {
        val previous = synchronized(custodyLock) {
            if (event != null && !event.affects(presentationActor)) return false
            actorVersion.incrementAndGet()
            reviewed?.raw?.fill('\u0000'); reviewed = null
            callback?.fill('\u0000'); callback = null; callbackActor = null
            work.also { work = null }
        }
        previous?.cancel()
        return true
    }
    private fun wipeOnMainAndAwait(after: () -> Unit = {}) {
        val wipe = { review?.dismiss(); review = null; profile.text.clear(); signIn.isEnabled = true; import.isEnabled = true; after() }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) wipe()
        else {
            val acknowledged = java.util.concurrent.CountDownLatch(1)
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)
            check(android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { wipe() } catch (_: Exception) { failed.set(true) } finally { acknowledged.countDown() }
            }) { "Private view wipe could not be posted" }
            var interrupted = false
            while (true) try { acknowledged.await(); break } catch (_: InterruptedException) { interrupted = true }
            if (interrupted) Thread.currentThread().interrupt()
            check(!failed.get()) { "Private view wipe failed" }
        }
    }
    override fun onDestroy() { started = false; retirePresentation(); retirement?.close(); retirement = null; wipeOnMainAndAwait(); super.onDestroy() }
    override fun onStop() {
        started = false; retirePresentation()
        // Stop invalidates in-flight publication but preserves the encrypted one-shot browser
        // transaction so a deliberate external-browser callback can finish on a started screen.
        presentationActor?.let(container.proxyCoordinator::suspendPresentation)
        wipeOnMainAndAwait(); super.onStop()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { const val EXTRA_CALLBACK_ID = "proxy_callback_id" }
}

/** Callback code remains process-only and never enters a Bundle. Lightweight internal IDs alone
 * cross Activities. The encrypted authorization transaction independently validates state/actor. */
internal object ProxyCallbackHandoff {
    internal class OwnedCallback(val callback: String, val actor: ScopedReadContext?)
    private class Entry(val callback: String, val deadline: Long, val actor: ScopedReadContext?)
    private val pending = LinkedHashMap<String, Entry>()
    @Synchronized fun put(callback: String, actor: ScopedReadContext? = null): String {
        require(callback.length <= 8192)
        pending.entries.removeAll { it.value.deadline <= android.os.SystemClock.elapsedRealtime() }
        while (pending.size >= 4) pending.remove(pending.keys.first())
        val id = java.util.UUID.randomUUID().toString()
        pending[id] = Entry(callback, android.os.SystemClock.elapsedRealtime() + 60_000, actor)
        return id
    }
    @Synchronized fun retire() { pending.clear() }
    @Synchronized fun retire(event: com.lateapex.collie.data.AuthorityRetirement) {
        // Unowned legacy entries grant no credentials; retire them conservatively.
        pending.entries.removeAll { it.value.actor == null || event.affects(it.value.actor) }
    }
    @Synchronized fun consumeOwned(id: String): OwnedCallback? = pending.remove(id)
        ?.takeIf { it.deadline > android.os.SystemClock.elapsedRealtime() }?.let { OwnedCallback(it.callback, it.actor) }
    @Synchronized fun consume(id: String): String? = consumeOwned(id)?.callback
}
