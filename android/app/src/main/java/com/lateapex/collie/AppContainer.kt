package com.lateapex.collie

import android.content.Context
import com.lateapex.collie.data.AndroidKeystoreCipher
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.EncryptedConnectionStore
import com.lateapex.collie.data.ScopedImageLoader
import com.lateapex.collie.data.LaunchIntentStore
import com.lateapex.collie.data.EncryptedQueuedSendAttemptStore
import com.lateapex.collie.data.PaneLayoutIntentStore
import com.lateapex.collie.data.EncryptedMachineRuleAttemptStore
import com.lateapex.collie.diagnostics.DiagnosticsRecorder
import com.lateapex.collie.diagnostics.DiagnosticsWriter
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.CollieApiClient
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.ui.NativePreferences
import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
    val nativePreferences = NativePreferences(context.applicationContext)
    val diagnosticsWriter = DiagnosticsWriter(
        directory = File(context.applicationContext.filesDir, "diagnostics"),
        cipher = AndroidKeystoreCipher(alias = "com.lateapex.collie.diagnostics.v1"),
        json = json,
    )
    var diagnostics: DiagnosticsRecorder = DiagnosticsRecorder(
        writer = diagnosticsWriter,
        enabled = { nativePreferences.diagnosticsEnabled },
    )
        @androidx.annotation.VisibleForTesting internal set
    val originValidator = OriginValidator()
    val connectionStore: ConnectionStore = EncryptedConnectionStore(
        context.applicationContext,
        AndroidKeystoreCipher(),
        json,
        originValidator,
    )
    val proxyIdentityStore = com.lateapex.collie.data.ProxyIdentityStore(
        context.applicationContext, AndroidKeystoreCipher(alias = "com.lateapex.collie.proxy.identity.v1"), json,
    )
    val proxyCoordinator = com.lateapex.collie.auth.ProxySignInCoordinator(
        proxyIdentityStore, com.lateapex.collie.auth.KeystoreProxySigningKey(),
        current = { connectionStore.connection.value }, context = { repository.readContext() }, actorCurrent = { repository.isCurrent(it) },
    )
    val proxyInterceptor = com.lateapex.collie.network.ProxyIdentityInterceptor(
        proxyCoordinator, current = { connectionStore.connection.value }, isCurrent = { repository.isCurrent(it) },
    )
    val httpClient: OkHttpClient = CollieApiClient.defaultHttpClient(diagnostics).newBuilder()
        .addInterceptor(proxyInterceptor).build()
    val queuedSendAttemptStore = EncryptedQueuedSendAttemptStore(
        context.applicationContext,
        AndroidKeystoreCipher(alias = "com.lateapex.collie.queued-send.v1"),
        json,
    )
    val paneLayoutIntentStore = PaneLayoutIntentStore(
        context.applicationContext,
        AndroidKeystoreCipher(alias = "com.lateapex.collie.layout-intents.v1", associatedData = "com.lateapex.collie.layout-intents.v1".encodeToByteArray()),
        json,
    )
    val api: CollieApi = CollieApiClient(httpClient, json)
    val launchIntentStore = LaunchIntentStore(
        context.applicationContext,
        AndroidKeystoreCipher(alias = "com.lateapex.collie.launch-intents.v1"),
        json,
    )
    val agentAutomationCustody = com.lateapex.collie.data.AgentAutomationCustody(context.applicationContext, json = json)
    val machineRuleAttemptStore = EncryptedMachineRuleAttemptStore(
        context.applicationContext,
        AndroidKeystoreCipher(alias = "com.lateapex.collie.machine-rule-attempts.v1"),
        json,
    )
    val repository = CollieRepository(
        api, connectionStore, originValidator,
        launchIntentStore = launchIntentStore,
        intentJson = json,
        machineRuleAttemptStore = machineRuleAttemptStore,
        offlineContentStore = nativePreferences.offlineContentStore,
    )
    val imageLoader = ScopedImageLoader(repository)
    internal val operatorTypefaceRestorer = com.lateapex.collie.ui.OperatorTypefaceRestorer(context.applicationContext, repository)
    val nativePush = com.lateapex.collie.notifications.NativePushRuntime(context.applicationContext, this)
    init {
        // Registered before Activity/View listeners: encrypted publication closes before any
        // main-thread wipe ACK can begin, with no monitor held across the later callbacks.
        repository.onReadContextRetired { event ->
            agentAutomationCustody.retire(event)
            com.lateapex.collie.ui.ProxyCallbackHandoff.retire(event)
            proxyCoordinator.retire(event)
            proxyInterceptor.retire(event)
        }
        repository.onAuthorityRetired { event ->
            nativePush.retire(event)
            val reason = event.reason
            if (reason == com.lateapex.collie.data.AuthorityInvalidation.ACCESS_FROZEN) {
                com.lateapex.collie.ui.ProxyCallbackHandoff.retire(event)
                proxyCoordinator.retire(event)
                proxyInterceptor.retire(event)
            }
            if (reason in setOf(com.lateapex.collie.data.AuthorityInvalidation.PAIRING_RETIRED,
                com.lateapex.collie.data.AuthorityInvalidation.CONNECTION_CHANGED, com.lateapex.collie.data.AuthorityInvalidation.DISCONNECTED)) {
                proxyCoordinator.retire(event, removeRecord = true)
                event.owner?.let(nativePreferences::retireSensitiveContent)
            }
        }
    }
}
