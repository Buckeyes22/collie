package com.lateapex.collie

import android.content.Context
import com.lateapex.collie.data.AndroidKeystoreCipher
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.EncryptedConnectionStore
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
    val httpClient: OkHttpClient = CollieApiClient.defaultHttpClient(diagnostics)
    val originValidator = OriginValidator()
    val connectionStore: ConnectionStore = EncryptedConnectionStore(
        context.applicationContext,
        AndroidKeystoreCipher(),
        json,
        originValidator,
    )
    val api: CollieApi = CollieApiClient(httpClient, json)
    val repository = CollieRepository(api, connectionStore, originValidator)
}
