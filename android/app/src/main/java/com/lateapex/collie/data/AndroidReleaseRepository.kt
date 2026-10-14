package com.lateapex.collie.data

import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.net.Uri
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Authenticator
import okhttp3.Call
import okio.BufferedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.DisposableHandle

internal data class AndroidReleaseCandidate(
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val notes: String,
    val sourceUrl: String,
    val byteSize: Long,
    val metadata: VerifiedAndroidRelease,
    val manifestDigest: String,
    val acceptedRevision: Long,
    val installedVersionCode: Long,
    val installedSignerDigests: List<String>,
    internal val apkUrl: HttpUrl,
    internal val metadataUrl: HttpUrl,
    internal val signatureUrl: HttpUrl,
)

@Serializable
internal data class AndroidReleaseOperationWitness(
    val operationId: String,
    val lifecycleState: String = "created",
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val manifestDigest: String,
    val metadataKeyId: String,
    val chain: List<MetadataKeyTransition>,
    val acceptedRevision: Long,
    val installedVersionCode: Long,
    val installedSignerDigests: List<String>,
    val rawMetadataBase64Url: String,
    val signatureBase64Url: String,
    val apkName: String,
    val apkSizeBytes: Long,
    val signerSha256: String,
    val minSdk: Int,
    val sourceUrl: String,
    val releaseNotes: String,
)

@Serializable
internal data class AndroidReleaseInstallAttempt(
    val attemptId: String,
    val operationId: String,
    val tag: String,
    val versionCode: Long,
    val manifestDigest: String,
    val signerSha256: String,
    val originalInstalledVersionCode: Long,
    val originalInstalledSigners: List<String>,
    val phase: String,
    val resultCode: Int? = null,
    val reconciledAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long,
    val installerProtocol: String = "view",
    val installerSessionId: Int? = null,
    val confirmationFilterUri: String? = null,
)

internal sealed interface AndroidInstallAdmission {
    data class Reserved(val attempt: AndroidReleaseInstallAttempt) : AndroidInstallAdmission
    data class Refused(val reason: String) : AndroidInstallAdmission
}

internal sealed interface AndroidReleaseLookup {
    data class Available(val candidate: AndroidReleaseCandidate) : AndroidReleaseLookup
    data object UpToDate : AndroidReleaseLookup
    data class Unavailable(val reason: Reason) : AndroidReleaseLookup

    enum class Reason {
        NETWORK,
        UNSIGNED_OR_INVALID,
        UNSUPPORTED_RELEASE,
        NO_COMPATIBLE_RELEASE,
        STORAGE,
    }
}

internal sealed interface AndroidApkDownload {
    data class Ready(val apk: File, val candidate: AndroidReleaseCandidate, val operationId: String) : AndroidApkDownload
    data class Failed(val reason: AndroidReleaseLookup.Reason) : AndroidApkDownload
}

/** Anonymous fixed-origin release reader. It deliberately never receives Collie connection state. */
internal class AndroidReleaseRepository(
    context: Context,
    private val client: OkHttpClient = anonymousClient(),
    private val apiRoot: HttpUrl = RELEASES_API,
    private val apiHost: String = API_HOST,
    private val releaseHost: String = "github.com",
    private val releaseHosts: Set<String> = RELEASE_HOSTS,
    private val allowedPort: Int = 443,
    private val verifier: AndroidReleaseMetadataVerifier = AndroidReleaseMetadataVerifier(context),
    private val packageName: String = context.packageName,
    private val preferences: SharedPreferences = context.getSharedPreferences(RELEASE_PREFS, Context.MODE_PRIVATE),
    private val installedVersionCodeOverrideForTests: (() -> Long?)? = null,
    private val installedSignerDigestsOverrideForTests: (() -> Set<String>?)? = null,
    private val beforeFinalInstallAdmissionForTests: suspend () -> Unit = {},
    private val archivePackageInfoForTests: ((File) -> PackageInfo?)? = null,
    private val afterDurableCancellationBeforeCleanupForTests: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private data class AcceptedState(
        val versionCode: Long,
        val digest: String,
        val chain: List<MetadataKeyTransition>,
        val keyId: String,
        val activationFloor: Long,
        val revision: Long,
    )

    private sealed interface AcceptedStateRead {
        data object Empty : AcceptedStateRead
        data class Valid(val state: AcceptedState) : AcceptedStateRead
        data object Corrupt : AcceptedStateRead
    }

    private sealed interface InstallAttemptRead {
        data object Empty : InstallAttemptRead
        data class Valid(val attempt: AndroidReleaseInstallAttempt) : InstallAttemptRead
        data object Corrupt : InstallAttemptRead
    }

    private val activeCalls: MutableMap<String, Call> = java.util.concurrent.ConcurrentHashMap()
    private val downloadingWitnesses: MutableMap<String, AndroidReleaseOperationWitness> = java.util.concurrent.ConcurrentHashMap()

    private val legacyMigrationUnavailable: Boolean

    init {
        legacyMigrationUnavailable = !migrateLegacyCanceledAttempt()
        if (!legacyMigrationUnavailable) cleanupArtifacts()
    }

    /** Old generic VIEW cancellation was not proof that the OS installer stopped. */
    private fun migrateLegacyCanceledAttempt(): Boolean = synchronized(TRUST_STATE_LOCK) {
        when (val original = readInstallAttempt(preferences, effectiveLegacyUnknown = false)) {
            InstallAttemptRead.Empty -> true
            InstallAttemptRead.Corrupt -> false
            is InstallAttemptRead.Valid -> {
                if (original.attempt.phase != INSTALL_CANCELED || original.attempt.installerProtocol != "view") true
                else try {
                    // Persist both ORIGINAL records before any artifact cleanup or publication.
                    // Failure still reads as effectively blocking UNKNOWN; no new reservation.
                    attemptEditor(original.attempt.copy(phase = INSTALL_UNKNOWN,
                        reconciledAtEpochMs = null, updatedAtEpochMs = System.currentTimeMillis())).commit()
                } catch (_: Exception) { false }
            }
        }
    }

    internal fun cancelActiveRequest(operationId: String) {
        // Snapshot exact original calls, then cancel outside every trust/custody lock.
        OWNED_CALLS.entries.filter { it.value.first === preferences && it.value.second == operationId }
            .map { it.key }.forEach { it.cancel() }
    }

    internal fun operationDirectory(root: File, operationId: String): File {
        require(OPERATION_ID.matches(operationId))
        return File(root, operationId)
    }

    /** At most one current operation owns artifacts. No recursive symlink traversal. */
    internal fun cleanupArtifacts(root: File = File(appContext.cacheDir, ApkDownloadWorker.UPDATE_DIRECTORY)) {
        synchronized(TRUST_STATE_LOCK) {
            val encoded = preferences.getString(KEY_OPERATION, null)
            val current = try { encoded?.let { json.decodeFromString<AndroidReleaseOperationWitness>(it) } }
                catch (_: Exception) { return@synchronized }
            // Corrupt/uncertain reservation is never interpreted as permission to revoke its grant.
            val install = readInstallAttempt(preferences)
            if (install is InstallAttemptRead.Corrupt) return@synchronized
            val blocking = (install as? InstallAttemptRead.Valid)?.attempt?.takeIf { it.phase in BLOCKING_INSTALL_PHASES }
            if (blocking != null && current?.operationId != blocking.operationId) return@synchronized
            if (encoded != null && (current == null || readOperation(current.operationId) == null)) return@synchronized
            // A genuinely blocking grant survives even if trust corruption prevents new admission.
            val keep = current?.takeIf { blocking?.operationId == it.operationId ||
                ((operationIsCurrentLocked(it) || readAcceptedState(preferences) is AcceptedStateRead.Corrupt) &&
                    it.lifecycleState !in setOf(OP_INSTALLED, OP_REFUSED)) }
            if (!root.isDirectory || root.canonicalFile != root.absoluteFile) return@synchronized
            java.nio.file.Files.newDirectoryStream(root.toPath()).use { entries ->
                for (entry in entries) {
                    if (keep != null && entry.fileName.toString() == keep.operationId) {
                        if (java.nio.file.Files.isSymbolicLink(entry) || !java.nio.file.Files.isDirectory(entry)) {
                            deleteArtifact(entry); continue
                        }
                        java.nio.file.Files.newDirectoryStream(entry).use { files ->
                            val base = keep.tag.removePrefix("android-v") + ".apk"
                            for (file in files) {
                                val name = file.fileName.toString()
                                val allowed = name == base + ".part" ||
                                    (name == base && (blocking?.operationId == keep.operationId || keep.lifecycleState !in setOf(OP_CANCELED, OP_FAILED)))
                                if (!allowed || java.nio.file.Files.isSymbolicLink(file) || !java.nio.file.Files.isRegularFile(file) || java.nio.file.Files.size(file) > AndroidReleaseMetadataVerifier.MAX_APK_BYTES)
                                    deleteArtifact(file)
                            }
                        }
                    } else if (keep != null && !java.nio.file.Files.isSymbolicLink(entry) &&
                        java.nio.file.Files.isRegularFile(entry) && java.nio.file.Files.size(entry) <= keep.apkSizeBytes &&
                        (entry.fileName.toString() == keep.tag.removePrefix("android-v") + ".apk.part" ||
                            (blocking?.operationId == keep.operationId && entry.fileName.toString() == keep.tag.removePrefix("android-v") + ".apk"))) {
                        // Bounded migration: a prior flat partial remains resumable; an existing
                        // UNKNOWN URI grant keeps its original inode until installed proof.
                    } else deleteArtifact(entry)
                }
            }
        }
    }

    private fun deleteArtifact(path: java.nio.file.Path) {
        java.nio.file.Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                java.nio.file.Files.deleteIfExists(file); return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, failure: IOException?): java.nio.file.FileVisitResult {
                if (failure != null) throw failure
                java.nio.file.Files.deleteIfExists(dir); return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    suspend fun latest(installedVersionCode: Long): AndroidReleaseLookup = withContext(Dispatchers.IO) {
        if (legacyMigrationUnavailable) return@withContext AndroidReleaseLookup.Unavailable(AndroidReleaseLookup.Reason.STORAGE)
        try {
            val operationId = UUID.randomUUID().toString()
            val releases = fetchReleases(operationId)
            val candidates = releases.asSequence()
                .filter { !it.draft && !it.prerelease && STABLE_TAG.matches(it.tagName) }
                .sortedWith { left, right -> compareVersions(right.tagName, left.tagName) }
                .toList()
            if (candidates.isEmpty()) return@withContext AndroidReleaseLookup.Unavailable(AndroidReleaseLookup.Reason.NO_COMPATIBLE_RELEASE)
            val release = candidates.first()
            val candidate = verifyRelease(release, operationId)
                ?: return@withContext AndroidReleaseLookup.Unavailable(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID)
            if (candidate.versionCode <= installedVersionCode) return@withContext AndroidReleaseLookup.UpToDate
            val accepted = admitMetadata(candidate.metadata)
                ?: return@withContext AndroidReleaseLookup.Unavailable(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID)
            val obsolete = synchronized(TRUST_STATE_LOCK) {
                currentOperation()?.takeIf { !operationIsCurrentLocked(it) }?.operationId
            }
            obsolete?.let { old -> markDownloadCanceled(old); ApkDownloadWorker.cancelQueuedOnly(appContext, old) }
            val installed = installedSnapshot()
                ?: return@withContext AndroidReleaseLookup.Unavailable(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID)
            if (installed.versionCode != installedVersionCode || installed.signers.isEmpty()) {
                return@withContext AndroidReleaseLookup.Unavailable(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID)
            }
            AndroidReleaseLookup.Available(candidate.copy(
                manifestDigest = sha256(candidate.metadata.rawMetadata),
                acceptedRevision = accepted.revision,
                installedVersionCode = installed.versionCode,
                installedSignerDigests = installed.signers.sorted(),
            ))
        } catch (_: ReleaseRequestException) {
            currentCoroutineContext().ensureActive()
            AndroidReleaseLookup.Unavailable(AndroidReleaseLookup.Reason.NETWORK)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            AndroidReleaseLookup.Unavailable(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID)
        }
    }

    suspend fun beginDownload(candidate: AndroidReleaseCandidate, operationId: String = UUID.randomUUID().toString()): AndroidReleaseOperationWitness? = withContext(Dispatchers.IO) {
        if (legacyMigrationUnavailable) return@withContext null
        if (!OPERATION_ID.matches(operationId) || candidate.acceptedRevision <= 0 || candidate.installedSignerDigests.isEmpty()) return@withContext null
        var superseded: String? = null
        var resumable: AndroidReleaseOperationWitness? = null
        val admitted = synchronized(TRUST_STATE_LOCK) {
            val accepted = (readAcceptedState(preferences) as? AcceptedStateRead.Valid)?.state ?: return@synchronized null
            val digest = sha256(candidate.metadata.rawMetadata)
            if (accepted.versionCode != candidate.versionCode || accepted.digest != digest ||
                accepted.revision != candidate.acceptedRevision || accepted.keyId != candidate.metadata.metadata.metadataKeyId ||
                accepted.chain != candidate.metadata.metadata.metadataKeyChain ||
                hasBlockingInstallAttempt(preferences)
            ) return@synchronized null
            val existingText = preferences.getString(KEY_OPERATION, null)
            if (existingText != null) {
                val existing = try { json.decodeFromString<AndroidReleaseOperationWitness>(existingText) }
                catch (_: Exception) { return@synchronized null }
                if (operationIsCurrentLocked(existing)) {
                    if (existing.manifestDigest == digest && existing.acceptedRevision == accepted.revision &&
                        existing.lifecycleState in ACTIVE_OPERATION_STATES + OP_READY
                    ) {
                        return@synchronized existing
                    }
                    if (existing.lifecycleState in ACTIVE_OPERATION_STATES) return@synchronized null
                }
                superseded = existing.operationId
                if (existing.manifestDigest == digest && existing.acceptedRevision == accepted.revision &&
                    existing.lifecycleState in setOf(OP_CANCELED, OP_FAILED)) resumable = existing
            }
            val witness = AndroidReleaseOperationWitness(
                operationId = operationId,
                tag = candidate.tag,
                versionName = candidate.versionName,
                versionCode = candidate.versionCode,
                manifestDigest = digest,
                metadataKeyId = candidate.metadata.metadata.metadataKeyId,
                chain = candidate.metadata.metadata.metadataKeyChain,
                acceptedRevision = accepted.revision,
                installedVersionCode = candidate.installedVersionCode,
                installedSignerDigests = candidate.installedSignerDigests,
                rawMetadataBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(candidate.metadata.rawMetadata),
                signatureBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(candidate.metadata.rawSignature),
                apkName = candidate.metadata.metadata.apk,
                apkSizeBytes = candidate.byteSize,
                signerSha256 = candidate.metadata.metadata.signingCertificateSha256,
                minSdk = candidate.metadata.metadata.minSdk,
                sourceUrl = candidate.sourceUrl,
                releaseNotes = candidate.notes,
            )
            if (!preferences.edit().putString(KEY_OPERATION, json.encodeToString(witness)).commit()) null else witness
        }
        if (admitted != null) {
            superseded?.let { old -> cancelActiveRequest(old); ApkDownloadWorker.cancelQueuedOnly(appContext, old) }
            // A new work UUID prevents a delayed CANCELLED emission from canceling resumed B.
            // Copy a bounded exact-manifest prefix to a distinct inode; never borrow A's file handle.
            resumable?.let { old ->
                val root = File(appContext.cacheDir, ApkDownloadWorker.UPDATE_DIRECTORY)
                val partName = old.tag.removePrefix("android-v") + ".apk.part"
                val owned = File(operationDirectory(root, old.operationId), partName)
                val original = if (owned.exists()) owned else File(root, partName)
                val destination = File(operationDirectory(root, admitted.operationId), partName)
                if (original.isFile && original.canonicalFile == original.absoluteFile && original.length() in 1 until old.apkSizeBytes) {
                    destination.parentFile?.mkdirs()
                    original.copyTo(destination, overwrite = false)
                }
            }
            cleanupArtifacts()
        }
        admitted
    }

    suspend fun download(
        operationId: String,
        directory: File,
        reportProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ): AndroidApkDownload = withContext(Dispatchers.IO) {
        val witness = readOperation(operationId)
            ?: return@withContext AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.UNSUPPORTED_RELEASE)
        if (!STABLE_TAG.matches(witness.tag)) return@withContext AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.UNSUPPORTED_RELEASE)
        if (!markOperationState(operationId, OP_DOWNLOADING)) return@withContext AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.UNSUPPORTED_RELEASE)
        val ownedDirectory = operationDirectory(directory, operationId)
        val part = File(ownedDirectory, "${witness.tag.removePrefix("android-v")}.apk.part")
        downloadingWitnesses[operationId] = witness
        try {
            ensureCurrentOperation(witness)
            val release = fetchReleases(operationId).singleOrNull { !it.draft && !it.prerelease && it.tagName == witness.tag }
                ?: return@withContext AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.UNSUPPORTED_RELEASE)
            val candidate = verifyRelease(release, operationId)
                ?: return@withContext AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID)
            if (!matchesWitness(candidate, witness)) {
                return@withContext AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID)
            }
            ensureCurrentOperation(witness)
            cleanupArtifacts(directory)
            if (!ownedDirectory.exists() && !ownedDirectory.mkdirs()) return@withContext AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.STORAGE)
            val available = StatFs(directory.parentFile?.absolutePath ?: directory.absolutePath).availableBytes
            if (available < candidate.byteSize + MIN_STAGING_RESERVE_BYTES) {
                return@withContext AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.STORAGE)
            }
            if (part.length() > candidate.byteSize) part.delete()
            fetchApk(candidate, part, operationId, reportProgress)
            val digest = sha256(part)
            if (digest != candidate.metadata.metadata.sha256) {
                part.delete()
                return@withContext AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID)
            }
            if (!archiveMatches(candidate.metadata.metadata, part)) {
                part.delete()
                return@withContext AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID)
            }
            ensureCurrentOperation(witness)
            val ready = File(ownedDirectory, "${witness.tag.removePrefix("android-v")}.apk")
            if (ready.exists() && !ready.delete()) throw IOException("Could not replace staged release")
            if (!part.renameTo(ready)) throw IOException("Could not finalize staged release")
            if (!markOperationState(operationId, OP_READY)) {
                ready.delete()
                return@withContext AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.STORAGE)
            }
            AndroidApkDownload.Ready(ready, candidate, operationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleOperationException) {
            part.delete()
            AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID)
        } catch (_: ReleaseRequestException) {
            currentCoroutineContext().ensureActive()
            AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.NETWORK)
        } catch (_: StorageWriteException) {
            currentCoroutineContext().ensureActive()
            part.delete()
            AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.STORAGE)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.NETWORK)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            part.delete()
            AndroidApkDownload.Failed(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID)
        } finally {
            downloadingWitnesses.remove(operationId, witness)
            cleanupArtifacts(directory)
        }
    }

    suspend fun reserveInstaller(operationId: String, file: File): AndroidInstallAdmission = withContext(Dispatchers.IO) {
        val witness = readOperation(operationId) ?: return@withContext AndroidInstallAdmission.Refused(REFUSAL_STALE)
        if (legacyMigrationUnavailable) return@withContext AndroidInstallAdmission.Refused(REFUSAL_PERSISTENCE)
        if (witness.lifecycleState != OP_READY) {
            return@withContext AndroidInstallAdmission.Refused(REFUSAL_STALE)
        }
        // Retired operations cannot borrow an artifact that cleanup already removed.
        // This pure original-witness check precedes filesystem work, without replacing
        // the final synchronized admission below.
        if (!synchronized(TRUST_STATE_LOCK) { operationIsReadyForInstallLocked(witness) }) {
            return@withContext AndroidInstallAdmission.Refused(REFUSAL_STALE)
        }
        val verified = verifiedWitness(witness) ?: return@withContext AndroidInstallAdmission.Refused(REFUSAL_TRUST)
        val canonical = canonicalStagedFile(file, witness) ?: return@withContext AndroidInstallAdmission.Refused(REFUSAL_FILE)
        if (canonical.length() != witness.apkSizeBytes || sha256(canonical) != witnessMetadataDigest(witness) ||
            !archiveMatches(verified.metadata, canonical)
        ) return@withContext AndroidInstallAdmission.Refused(REFUSAL_FILE)
        beforeFinalInstallAdmissionForTests()
        if (!synchronized(TRUST_STATE_LOCK) { operationIsReadyForInstallLocked(witness) }) {
            return@withContext AndroidInstallAdmission.Refused(REFUSAL_STALE)
        }
        val finalFile = canonicalStagedFile(file, witness) ?: return@withContext AndroidInstallAdmission.Refused(REFUSAL_FILE)
        if (finalFile.length() != witness.apkSizeBytes || sha256(finalFile) != witnessMetadataDigest(witness) ||
            !archiveMatches(verified.metadata, finalFile)
        ) return@withContext AndroidInstallAdmission.Refused(REFUSAL_FILE)
        synchronized(TRUST_STATE_LOCK) {
            if (!operationIsReadyForInstallLocked(witness)) return@synchronized AndroidInstallAdmission.Refused(REFUSAL_STALE)
            val installed = installedSnapshot() ?: return@synchronized AndroidInstallAdmission.Refused(REFUSAL_INSTALLED_IDENTITY)
            if (installed.versionCode != witness.installedVersionCode || installed.signers.sorted() != witness.installedSignerDigests.sorted() ||
                installed.versionCode >= witness.versionCode
            ) return@synchronized AndroidInstallAdmission.Refused(REFUSAL_INSTALLED_CHANGED)
            val previous = readInstallAttempt(preferences)
            if (previous is InstallAttemptRead.Corrupt) return@synchronized AndroidInstallAdmission.Refused(REFUSAL_TRUST)
            val prior = (previous as? InstallAttemptRead.Valid)?.attempt
            if (prior != null && prior.phase in BLOCKING_INSTALL_PHASES) return@synchronized AndroidInstallAdmission.Refused(REFUSAL_INSTALL_PENDING)
            val attempt = AndroidReleaseInstallAttempt(
                attemptId = UUID.randomUUID().toString(), operationId = operationId, tag = witness.tag,
                versionCode = witness.versionCode, manifestDigest = witness.manifestDigest,
                signerSha256 = witness.signerSha256, originalInstalledVersionCode = installed.versionCode,
                originalInstalledSigners = installed.signers.sorted(), phase = INSTALL_PREPARED,
                updatedAtEpochMs = System.currentTimeMillis(), installerProtocol = SESSION_PROTOCOL,
            )
            val preparedOperation = witness.copy(lifecycleState = OP_INSTALL_PREPARED)
            if (!preferences.edit()
                    .putString(KEY_INSTALL_ATTEMPT, json.encodeToString(attempt))
                    .putString(KEY_OPERATION, json.encodeToString(preparedOperation))
                    .commit()
            ) {
                AndroidInstallAdmission.Refused(REFUSAL_PERSISTENCE)
            } else AndroidInstallAdmission.Reserved(attempt)
        }
    }

    /** Stage exact verified bytes in a private Android session before committing it. */
    suspend fun prepareInstallerSession(attemptId: String, file: File): Int? = withContext(Dispatchers.IO) {
        val original = currentInstallAttempt()?.takeIf {
            it.attemptId == attemptId && it.phase == INSTALL_PREPARED && it.installerProtocol == SESSION_PROTOCOL && it.installerSessionId == null
        } ?: return@withContext null
        val witness = readOperation(original.operationId) ?: return@withContext null
        val metadata = verifiedWitness(witness)?.metadata ?: return@withContext null
        val apk = canonicalStagedFile(file, witness) ?: return@withContext null
        val installer = appContext.packageManager.packageInstaller
        var sessionId: Int? = null
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(PRODUCTION_PACKAGE)
                setInstallerPackageName(appContext.packageName)
                setSize(witness.apkSizeBytes)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
            val created = installer.createSession(params)
            sessionId = created
            val attached = synchronized(TRUST_STATE_LOCK) {
                val current = currentInstallAttempt()
                if (current != original) false else attemptEditor(original.copy(installerSessionId = created,
                    updatedAtEpochMs = System.currentTimeMillis())).commit()
            }
            if (!attached) throw IOException("Session custody unavailable")
            installer.openSession(created).use { session ->
                session.openWrite("base.apk", 0, witness.apkSizeBytes).use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    apk.inputStream().use { input ->
                        val bytes = ByteArray(16 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(bytes)
                            if (read < 0) break
                            count += read
                            if (count > witness.apkSizeBytes) throw IOException("Changed staged bytes")
                            digest.update(bytes, 0, read)
                            output.write(bytes, 0, read)
                        }
                    }
                    if (count != witness.apkSizeBytes || digest.digest().joinToString("") { "%02x".format(it) } != metadata.sha256) {
                        throw IOException("Changed staged bytes")
                    }
                    session.fsync(output)
                }
            }
            created
        } catch (failure: Exception) {
            sessionId?.let(::abandonSession)
            failInstallerDispatch(attemptId)
            if (failure is CancellationException) throw failure
            null
        }
    }

    /** Called only after the original foreground review remains current. Never auto-replayed. */
    fun commitInstallerSession(attemptId: String, sessionId: Int): Boolean {
        val attempt = currentInstallAttempt()?.takeIf {
            it.attemptId == attemptId && it.installerSessionId == sessionId && it.phase == INSTALL_PREPARED
        } ?: return false
        val admitted = synchronized(TRUST_STATE_LOCK) {
            val witness = readOperation(attempt.operationId)
            val installed = installedSnapshot()
            if (currentInstallAttempt() != attempt || witness == null || !operationIsCurrentLocked(witness) ||
                witness.lifecycleState != OP_INSTALL_PREPARED || installed == null ||
                installed.versionCode != attempt.originalInstalledVersionCode ||
                installed.signers.sorted() != attempt.originalInstalledSigners.sorted() ||
                installed.versionCode >= attempt.versionCode) false
            else attemptEditor(attempt.copy(phase = INSTALL_DISPATCHED, updatedAtEpochMs = System.currentTimeMillis())).commit()
        }
        if (!admitted) { abandonPreparedInstaller(attemptId); return false }
        val intent = Intent(appContext, AndroidReleaseInstallReceiver::class.java).apply {
            action = AndroidReleaseInstallReceiver.RESULT_ACTION
            data = Uri.parse("collie-install:$attemptId")
        }
        // PackageInstaller fills status/session fields; the explicit component and unique data
        // retain this attempt's callback capability. Android 35+ refuses immutable senders.
        val callback = PendingIntent.getBroadcast(appContext, sessionId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        return try {
            appContext.packageManager.packageInstaller.openSession(sessionId).use { it.commit(callback.intentSender) }
            // A very fast OS callback may already have advanced/finished the attempt.
            markInstallerPending(attemptId)
            true
        } catch (_: Exception) {
            val session = try { appContext.packageManager.packageInstaller.getSessionInfo(sessionId) } catch (_: Exception) { null }
            if (session?.isCommitted == false) {
                abandonSession(sessionId)
                failInstallerDispatch(attemptId)
            } else {
                // An absent session after IPC failure is ambiguous; the OS may have consumed it.
                updateAttempt(attemptId, setOf(INSTALL_DISPATCHED, INSTALL_PENDING)) {
                    it.copy(phase = INSTALL_UNKNOWN, updatedAtEpochMs = System.currentTimeMillis())
                }
            }
            false
        }
    }

    internal fun abandonPreparedInstaller(attemptId: String) {
        val attempt = currentInstallAttempt()?.takeIf { it.attemptId == attemptId && it.phase == INSTALL_PREPARED } ?: return
        attempt.installerSessionId?.let(::abandonSession)
        failInstallerDispatch(attemptId)
    }

    private fun abandonSession(sessionId: Int) {
        try { appContext.packageManager.packageInstaller.abandonSession(sessionId) } catch (_: Exception) { }
    }

    /** Only the nonexported, attempt-specific Android callback calls this method. */
    internal suspend fun recordInstallerSessionResult(
        attemptId: String, sessionId: Int, status: Int, confirmation: Intent?,
    ): AndroidReleaseInstallAttempt? = withContext(Dispatchers.IO) {
        val installed = installedSnapshot()
        synchronized(TRUST_STATE_LOCK) {
            val current = currentInstallAttempt()?.takeIf {
                it.attemptId == attemptId && it.installerProtocol == SESSION_PROTOCOL &&
                    it.installerSessionId == sessionId && it.phase in setOf(INSTALL_DISPATCHED, INSTALL_PENDING, INSTALL_UNKNOWN)
            } ?: return@synchronized null
            val installedTarget = installed?.versionCode == current.versionCode && installed.signers.contains(current.signerSha256)
            val pendingUri = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && confirmation != null && confirmation.selector == null) {
                val filter = confirmation.cloneFilter().toUri(Intent.URI_INTENT_SCHEME).takeIf { it.length <= 16384 }
                if (filter != null) {
                    // Preserve the OS-created Intent, including its Android16+ creator token,
                    // in Android's PendingIntent store. Persist only lookup identity, never a
                    // serialized replacement Intent that would lose launch security metadata.
                    PendingIntent.getActivity(appContext, sessionId, confirmation,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                }
                filter
            } else null
            val phase = when {
                installedTarget -> INSTALL_INSTALLED
                status == PackageInstaller.STATUS_PENDING_USER_ACTION && pendingUri != null -> INSTALL_PENDING
                status == PackageInstaller.STATUS_FAILURE_ABORTED -> INSTALL_CANCELED
                status in setOf(PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_BLOCKED,
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                    PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_STORAGE,
                    PackageInstaller.STATUS_FAILURE_TIMEOUT) -> INSTALL_REFUSED
                else -> INSTALL_UNKNOWN
            }
            val next = current.copy(phase = phase, resultCode = status, confirmationFilterUri = pendingUri,
                reconciledAtEpochMs = System.currentTimeMillis(), updatedAtEpochMs = System.currentTimeMillis())
            if (attemptEditor(next).commit()) {
                if (phase !in BLOCKING_INSTALL_PHASES) retireConfirmationCapability(current)
                next
            } else null
        }
    }.also { cleanupArtifacts() }

    internal fun pendingInstallerConfirmation(): PendingIntent? {
        val attempt = currentInstallAttempt()?.takeIf {
            it.installerProtocol == SESSION_PROTOCOL && it.phase == INSTALL_PENDING && it.installerSessionId != null
        } ?: return null
        return try {
            val session = appContext.packageManager.packageInstaller.getSessionInfo(requireNotNull(attempt.installerSessionId))
                ?: return null
            if (session.installerPackageName != appContext.packageName || session.appPackageName != PRODUCTION_PACKAGE) return null
            val filter = Intent.parseUri(attempt.confirmationFilterUri ?: return null, Intent.URI_INTENT_SCHEME)
            PendingIntent.getActivity(appContext, requireNotNull(attempt.installerSessionId), filter,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        } catch (_: Exception) { null }
    }

    /** A deliberate cancel retires this owned Android session; no generic VIEW inference. */
    internal suspend fun cancelPendingInstaller(attemptId: String): Boolean = withContext(Dispatchers.IO) {
        val original = currentInstallAttempt()?.takeIf {
            it.attemptId == attemptId && it.installerProtocol == SESSION_PROTOCOL &&
                it.phase in BLOCKING_INSTALL_PHASES && it.installerSessionId != null
        } ?: return@withContext false
        val sessionId = requireNotNull(original.installerSessionId)
        val installer = appContext.packageManager.packageInstaller
        try {
            val owned = installer.getSessionInfo(sessionId) ?: return@withContext false
            if (owned.installerPackageName != appContext.packageName) return@withContext false
            installer.abandonSession(sessionId)
            synchronized(TRUST_STATE_LOCK) {
                val current = currentInstallAttempt()?.takeIf { it.attemptId == original.attemptId } ?: return@synchronized false
                if (current.phase !in BLOCKING_INSTALL_PHASES) return@synchronized current.phase == INSTALL_CANCELED
                val installed = installedSnapshot()
                val phase = if (installed?.versionCode == current.versionCode && installed.signers.contains(current.signerSha256))
                    INSTALL_INSTALLED else INSTALL_CANCELED
                val next = current.copy(phase = phase, confirmationFilterUri = null,
                    reconciledAtEpochMs = System.currentTimeMillis(), updatedAtEpochMs = System.currentTimeMillis())
                val stored = attemptEditor(next).commit()
                if (stored) retireConfirmationCapability(current)
                stored
            }
        } catch (_: Exception) { false }
    }.also { cleanupArtifacts() }

    private fun retireConfirmationCapability(attempt: AndroidReleaseInstallAttempt) {
        try {
            val id = attempt.installerSessionId ?: return
            val filter = Intent.parseUri(attempt.confirmationFilterUri ?: return, Intent.URI_INTENT_SCHEME)
            PendingIntent.getActivity(appContext, id, filter,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.cancel()
        } catch (_: Exception) { }
    }

    internal fun addInstallListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = preferences.registerOnSharedPreferenceChangeListener(listener)
    internal fun removeInstallListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = preferences.unregisterOnSharedPreferenceChangeListener(listener)

    fun markInstallerDispatched(attemptId: String): Boolean = updateAttempt(attemptId, setOf(INSTALL_PREPARED)) { it.copy(phase = INSTALL_DISPATCHED, updatedAtEpochMs = System.currentTimeMillis()) }

    fun markInstallerPending(attemptId: String): Boolean = updateAttempt(attemptId, setOf(INSTALL_DISPATCHED)) { it.copy(phase = INSTALL_PENDING, updatedAtEpochMs = System.currentTimeMillis()) }

    fun failInstallerDispatch(attemptId: String): Boolean = updateAttempt(attemptId, setOf(INSTALL_PREPARED, INSTALL_DISPATCHED)) { it.copy(phase = INSTALL_REFUSED, updatedAtEpochMs = System.currentTimeMillis()) }

    suspend fun recordInstallerResult(attemptId: String, resultCode: Int): AndroidReleaseInstallAttempt? = withContext(Dispatchers.IO) {
        val attempt = currentInstallAttempt() ?: return@withContext null
        if (attempt.attemptId != attemptId) return@withContext attempt
        val installed = installedSnapshot()
        synchronized(TRUST_STATE_LOCK) {
            val current = (readInstallAttempt(preferences) as? InstallAttemptRead.Valid)?.attempt ?: return@synchronized null
            if (current.installerProtocol != "view" || current.attemptId != attempt.attemptId || current.phase !in setOf(INSTALL_DISPATCHED, INSTALL_PENDING)) return@synchronized current
            val installedTarget = installed?.versionCode == current.versionCode && installed.signers.contains(current.signerSha256)
            val next = current.copy(
                // ACTION_VIEW has no authoritative installer-cancellation protocol.
                phase = if (installedTarget) INSTALL_INSTALLED else INSTALL_UNKNOWN,
                resultCode = resultCode, reconciledAtEpochMs = System.currentTimeMillis(), updatedAtEpochMs = System.currentTimeMillis(),
            )
            if (attemptEditor(next).commit()) next else null
        }
    }.also { cleanupArtifacts() }

    suspend fun reconcileInstallAttempt(): AndroidReleaseInstallAttempt? = withContext(Dispatchers.IO) {
        val before = currentInstallAttempt() ?: return@withContext null
        val installed = installedSnapshot()
        synchronized(TRUST_STATE_LOCK) {
            val current = (readInstallAttempt(preferences) as? InstallAttemptRead.Valid)?.attempt ?: return@synchronized null
            if (current.attemptId != before.attemptId) return@synchronized current
            if (current.phase !in setOf(INSTALL_PREPARED, INSTALL_DISPATCHED, INSTALL_PENDING, INSTALL_UNKNOWN)) return@synchronized current
            val installedTarget = installed?.versionCode == current.versionCode && installed.signers.contains(current.signerSha256)
            val phase = when {
                installedTarget -> INSTALL_INSTALLED
                current.phase == INSTALL_PREPARED && current.installerSessionId?.let {
                    appContext.packageManager.packageInstaller.getSessionInfo(it)?.isCommitted
                } != true -> INSTALL_REFUSED
                current.installerProtocol == SESSION_PROTOCOL && current.confirmationFilterUri != null -> INSTALL_PENDING
                else -> INSTALL_UNKNOWN
            }
            val next = current.copy(phase = phase, reconciledAtEpochMs = System.currentTimeMillis(), updatedAtEpochMs = System.currentTimeMillis())
            if (attemptEditor(next).commit()) {
                if (phase == INSTALL_REFUSED) current.installerSessionId?.let(::abandonSession)
                next
            } else null
        }
    }.also { cleanupArtifacts() }

    fun currentInstallAttempt(): AndroidReleaseInstallAttempt? = synchronized(TRUST_STATE_LOCK) {
        (readInstallAttempt(preferences) as? InstallAttemptRead.Valid)?.attempt
    }

    fun getOperation(operationId: String): AndroidReleaseOperationWitness? = readOperation(operationId)

    fun currentOperation(): AndroidReleaseOperationWitness? = synchronized(TRUST_STATE_LOCK) {
        val encoded = preferences.getString(KEY_OPERATION, null) ?: return@synchronized null
        val operationId = try { json.decodeFromString<AndroidReleaseOperationWitness>(encoded).operationId }
        catch (_: Exception) { return@synchronized null }
        readOperation(operationId)
    }

    private fun markOperationState(operationId: String, lifecycleState: String): Boolean = synchronized(TRUST_STATE_LOCK) {
        val current = readOperation(operationId) ?: return@synchronized false
        if (current.lifecycleState in setOf(OP_INSTALL_PREPARED, OP_INSTALL_DISPATCHED, OP_INSTALL_PENDING, OP_UNKNOWN, OP_INSTALLED, OP_CANCELED, OP_REFUSED)) {
            return@synchronized false
        }
        preferences.edit().putString(KEY_OPERATION, json.encodeToString(current.copy(lifecycleState = lifecycleState))).commit()
    }

    internal fun markDownloadCanceled(operationId: String): Boolean {
        val changed = markOperationState(operationId, OP_CANCELED)
        // The acknowledgement is complete and the trust lock is released here.
        if (changed) afterDurableCancellationBeforeCleanupForTests(operationId)
        cancelActiveRequest(operationId)
        cleanupArtifacts()
        return changed
    }

    internal fun markDownloadFailed(operationId: String): Boolean = markOperationState(operationId, OP_FAILED)

    private fun updateAttempt(attemptId: String, allowed: Set<String>, transform: (AndroidReleaseInstallAttempt) -> AndroidReleaseInstallAttempt): Boolean = synchronized(TRUST_STATE_LOCK) {
        val current = (readInstallAttempt(preferences) as? InstallAttemptRead.Valid)?.attempt ?: return@synchronized false
        if (current.attemptId != attemptId || current.phase !in allowed) return@synchronized false
        val next = transform(current)
        attemptEditor(next).commit()
    }.also { cleanupArtifacts() }

    private fun attemptEditor(attempt: AndroidReleaseInstallAttempt): SharedPreferences.Editor {
        val editor = preferences.edit().putString(KEY_INSTALL_ATTEMPT, json.encodeToString(attempt))
        val operation = readOperation(attempt.operationId)
        if (operation != null) {
            editor.putString(KEY_OPERATION, json.encodeToString(operation.copy(lifecycleState = operationStateFor(attempt.phase))))
        }
        return editor
    }

    private fun operationStateFor(phase: String): String = when (phase) {
        INSTALL_PREPARED -> OP_INSTALL_PREPARED
        INSTALL_DISPATCHED -> OP_INSTALL_DISPATCHED
        INSTALL_PENDING -> OP_INSTALL_PENDING
        INSTALL_INSTALLED -> OP_INSTALLED
        INSTALL_CANCELED -> OP_CANCELED
        INSTALL_REFUSED -> OP_REFUSED
        else -> OP_UNKNOWN
    }

    private fun readOperation(operationId: String): AndroidReleaseOperationWitness? = synchronized(TRUST_STATE_LOCK) {
        if (!OPERATION_ID.matches(operationId)) return@synchronized null
        val encoded = preferences.getString(KEY_OPERATION, null) ?: return@synchronized null
        try {
            json.decodeFromString<AndroidReleaseOperationWitness>(encoded).takeIf {
                it.operationId == operationId && it.lifecycleState in OPERATION_STATES && STABLE_TAG.matches(it.tag) &&
                    it.versionCode > 0 && it.acceptedRevision > 0 && HEX_SHA256.matches(it.manifestDigest) &&
                    it.apkSizeBytes in 1..AndroidReleaseMetadataVerifier.MAX_APK_BYTES && it.installedVersionCode >= 0 &&
                    it.installedSignerDigests.isNotEmpty() && it.installedSignerDigests.all(HEX_SHA256::matches) &&
                    it.sourceUrl == "https://github.com/Buckeyes22/collie/releases/tag/${it.tag}" &&
                    it.releaseNotes.length <= MAX_RELEASE_NOTES_CHARS
            }?.let { operation ->
                val attempt = (readInstallAttempt(preferences) as? InstallAttemptRead.Valid)?.attempt
                if (operation.lifecycleState == OP_CANCELED && attempt != null && attempt.operationId == operation.operationId && attempt.phase == INSTALL_UNKNOWN)
                    operation.copy(lifecycleState = OP_UNKNOWN) else operation
            }
        }
        catch (_: Exception) { null }
    }

    private fun verifiedWitness(witness: AndroidReleaseOperationWitness): VerifiedAndroidRelease? {
        val raw = try { decodeStrictBase64Url(witness.rawMetadataBase64Url) } catch (_: Exception) { return null }
        val signatureText = witness.signatureBase64Url.toByteArray(Charsets.US_ASCII)
        val result = verifier.verify(raw, signatureText, 0L, Build.VERSION.SDK_INT)
        val verified = (result as? ReleaseMetadataResult.Verified)?.release ?: return null
        if (sha256(raw) != witness.manifestDigest || verified.metadata.versionCode != witness.versionCode ||
            verified.metadata.tag != witness.tag || verified.metadata.metadataKeyId != witness.metadataKeyId ||
            verified.metadata.metadataKeyChain != witness.chain || verified.metadata.apk != witness.apkName ||
            verified.metadata.apkSizeBytes != witness.apkSizeBytes || verified.metadata.signingCertificateSha256 != witness.signerSha256
        ) return null
        return verified
    }

    suspend fun reserveInstallerFor(operationId: String, file: File): AndroidInstallAdmission = reserveInstaller(operationId, file)

    private suspend fun verifyStagedFile(operationId: String, file: File): Pair<AndroidReleaseOperationWitness, AndroidReleaseMetadata>? = withContext(Dispatchers.IO) {
        val witness = readOperation(operationId) ?: return@withContext null
        val metadata = verifiedWitness(witness)?.metadata ?: return@withContext null
        val canonical = canonicalStagedFile(file, witness) ?: return@withContext null
        if (canonical.length() != witness.apkSizeBytes || sha256(canonical) != metadata.sha256 || !archiveMatches(metadata, canonical)) return@withContext null
        witness to metadata
    }

    private fun canonicalStagedFile(file: File, witness: AndroidReleaseOperationWitness): File? = try {
        val canonical = file.canonicalFile
        val updates = File(appContext.cacheDir, ApkDownloadWorker.UPDATE_DIRECTORY).canonicalFile
        canonical.takeIf { it.parentFile == operationDirectory(updates, witness.operationId) && it.name == witness.tag.removePrefix("android-v") + ".apk" && it.isFile && it.length() in 1..AndroidReleaseMetadataVerifier.MAX_APK_BYTES }
    } catch (_: IOException) { null }

    private fun witnessMetadataDigest(witness: AndroidReleaseOperationWitness): String? = verifiedWitness(witness)?.metadata?.sha256

    private fun matchesWitness(candidate: AndroidReleaseCandidate, witness: AndroidReleaseOperationWitness): Boolean =
        candidate.tag == witness.tag && candidate.versionCode == witness.versionCode &&
            sha256(candidate.metadata.rawMetadata) == witness.manifestDigest &&
            candidate.metadata.metadata.metadataKeyId == witness.metadataKeyId &&
            candidate.metadata.metadata.metadataKeyChain == witness.chain &&
            candidate.metadata.metadata.apkSizeBytes == witness.apkSizeBytes &&
            candidate.metadata.metadata.signingCertificateSha256 == witness.signerSha256

    private fun ensureCurrentOperation(witness: AndroidReleaseOperationWitness) {
        if (!synchronized(TRUST_STATE_LOCK) {
            operationIsCurrentLocked(witness) && readOperation(witness.operationId)?.lifecycleState in setOf(OP_CREATED, OP_DOWNLOADING)
        }) throw StaleOperationException()
    }

    /** Installer admission cannot normalize a committed terminal phase back to ready. */
    private fun operationIsReadyForInstallLocked(witness: AndroidReleaseOperationWitness): Boolean =
        witness.lifecycleState == OP_READY && operationIsCurrentLocked(witness) &&
            readOperation(witness.operationId)?.lifecycleState == OP_READY

    private fun operationIsCurrentLocked(witness: AndroidReleaseOperationWitness): Boolean {
        val read = readAcceptedState(preferences)
        val state = (read as? AcceptedStateRead.Valid)?.state ?: return false
        val current = try { json.decodeFromString<AndroidReleaseOperationWitness>(preferences.getString(KEY_OPERATION, null) ?: return false) }
            catch (_: Exception) { return false }
        return current.copy(lifecycleState = witness.lifecycleState) == witness && state.revision == witness.acceptedRevision &&
            state.versionCode == witness.versionCode && state.digest == witness.manifestDigest &&
            state.keyId == witness.metadataKeyId && state.chain == witness.chain
    }

    private data class InstalledSnapshot(val versionCode: Long, val signers: Set<String>)

    private fun installedSnapshot(): InstalledSnapshot? {
        return try {
            val versionOverride = installedVersionCodeOverrideForTests?.invoke()
            val signerOverride = installedSignerDigestsOverrideForTests?.invoke()
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION")
            val info = appContext.packageManager.getPackageInfo(PRODUCTION_PACKAGE, flags)
            @Suppress("DEPRECATION")
            val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            InstalledSnapshot(versionOverride ?: version, signerOverride ?: signerDigests(info))
        } catch (_: Exception) { null }
    }

    private fun readAcceptedState(prefs: SharedPreferences): AcceptedStateRead {
        val keys = listOf(KEY_ACCEPTED_VERSION, KEY_ACCEPTED_DIGEST, KEY_ACCEPTED_CHAIN, KEY_ACCEPTED_KEY_ID, KEY_ACCEPTED_ACTIVATION, KEY_ACCEPTED_REVISION)
        val present = keys.count(prefs::contains)
        if (present == 0) return AcceptedStateRead.Empty
        if (present != keys.size) return AcceptedStateRead.Corrupt
        return try {
            val version = prefs.getLong(KEY_ACCEPTED_VERSION, -1L)
            val digest = prefs.getString(KEY_ACCEPTED_DIGEST, null) ?: return AcceptedStateRead.Corrupt
            val chainText = prefs.getString(KEY_ACCEPTED_CHAIN, null) ?: return AcceptedStateRead.Corrupt
            val chain = json.decodeFromString(ListSerializer(MetadataKeyTransition.serializer()), chainText)
            val keyId = prefs.getString(KEY_ACCEPTED_KEY_ID, null) ?: return AcceptedStateRead.Corrupt
            val floor = prefs.getLong(KEY_ACCEPTED_ACTIVATION, -1L)
            val revision = prefs.getLong(KEY_ACCEPTED_REVISION, -1L)
            val expectedKey = chain.lastOrNull()?.toKeyId ?: verifier.pinnedKeyId()
            if (version <= 0 || revision <= 0 || floor < 0 || chain.size > AndroidReleaseMetadataVerifier.MAX_TRANSITIONS ||
                !HEX_SHA256.matches(digest) || !HEX_SHA256.matches(keyId) || expectedKey != keyId ||
                floor != (chain.lastOrNull()?.activationVersionCode ?: 0L)
            ) AcceptedStateRead.Corrupt else AcceptedStateRead.Valid(AcceptedState(version, digest, chain, keyId, floor, revision))
        } catch (_: Exception) { AcceptedStateRead.Corrupt }
    }

    private fun readInstallAttempt(prefs: SharedPreferences, effectiveLegacyUnknown: Boolean = true): InstallAttemptRead {
        if (!prefs.contains(KEY_INSTALL_ATTEMPT)) return InstallAttemptRead.Empty
        return try {
            val encoded = prefs.getString(KEY_INSTALL_ATTEMPT, null) ?: return InstallAttemptRead.Corrupt
            val attempt = json.decodeFromString<AndroidReleaseInstallAttempt>(encoded)
            if (!OPERATION_ID.matches(attempt.attemptId) || !OPERATION_ID.matches(attempt.operationId) ||
                attempt.phase !in INSTALL_PHASES || attempt.installerProtocol !in setOf("view", SESSION_PROTOCOL) ||
                (attempt.installerSessionId != null && attempt.installerSessionId < 0) ||
                (attempt.confirmationFilterUri?.length ?: 0) > 16384 || attempt.versionCode <= 0 || !HEX_SHA256.matches(attempt.manifestDigest) ||
                !HEX_SHA256.matches(attempt.signerSha256) || attempt.originalInstalledVersionCode < 0 || attempt.originalInstalledSigners.isEmpty()
            ) InstallAttemptRead.Corrupt else InstallAttemptRead.Valid(
                if (effectiveLegacyUnknown && attempt.phase == INSTALL_CANCELED && attempt.installerProtocol == "view")
                    attempt.copy(phase = INSTALL_UNKNOWN, reconciledAtEpochMs = null) else attempt,
            )
        } catch (_: Exception) { InstallAttemptRead.Corrupt }
    }

    private fun hasBlockingInstallAttempt(prefs: SharedPreferences): Boolean = when (val attempt = readInstallAttempt(prefs)) {
        InstallAttemptRead.Empty -> false
        InstallAttemptRead.Corrupt -> true
        is InstallAttemptRead.Valid -> attempt.attempt.phase in BLOCKING_INSTALL_PHASES
    }

    private fun incomingChainAllowed(state: AcceptedState, verified: VerifiedAndroidRelease): Boolean {
        val incoming = verified.metadata.metadataKeyChain
        val rootOrTerminal = state.chain.lastOrNull()?.toKeyId ?: state.keyId
        val startsFromAccepted = if (state.chain.isEmpty()) (incoming.firstOrNull()?.fromKeyId ?: verified.metadata.metadataKeyId) == rootOrTerminal
            else state.chain.last().toKeyId == rootOrTerminal
        return startsFromAccepted && incoming.size >= state.chain.size && incoming.take(state.chain.size) == state.chain &&
            (incoming.lastOrNull()?.activationVersionCode ?: 0L) >= state.activationFloor
    }

    private fun admitMetadata(verified: VerifiedAndroidRelease): AcceptedState? = synchronized(TRUST_STATE_LOCK) {
        val currentRead = readAcceptedState(preferences)
        val current = when (currentRead) {
            AcceptedStateRead.Empty -> null
            AcceptedStateRead.Corrupt -> return@synchronized null
            is AcceptedStateRead.Valid -> currentRead.state
        }
        val digest = sha256(verified.rawMetadata)
        if (current != null) {
            if (verified.metadata.versionCode < current.versionCode || !incomingChainAllowed(current, verified)) return@synchronized null
            if (verified.metadata.versionCode == current.versionCode && digest != current.digest) return@synchronized null
            if (hasBlockingInstallAttempt(preferences) && (verified.metadata.versionCode != current.versionCode || digest != current.digest)) return@synchronized null
            if (verified.metadata.versionCode == current.versionCode && digest == current.digest) return@synchronized current
        } else if (hasBlockingInstallAttempt(preferences)) return@synchronized null
        val chain = verified.metadata.metadataKeyChain
        val next = AcceptedState(
            versionCode = verified.metadata.versionCode, digest = digest, chain = chain,
            keyId = verified.metadata.metadataKeyId, activationFloor = chain.lastOrNull()?.activationVersionCode ?: 0L,
            revision = (current?.revision ?: 0L) + 1L,
        )
        val stored = preferences.edit()
            .putLong(KEY_ACCEPTED_VERSION, next.versionCode)
            .putString(KEY_ACCEPTED_DIGEST, next.digest)
            .putString(KEY_ACCEPTED_CHAIN, json.encodeToString(ListSerializer(MetadataKeyTransition.serializer()), next.chain))
            .putString(KEY_ACCEPTED_KEY_ID, next.keyId)
            .putLong(KEY_ACCEPTED_ACTIVATION, next.activationFloor)
            .putLong(KEY_ACCEPTED_REVISION, next.revision)
            .commit()
        if (stored) next else null
    }

    private suspend fun fetchReleases(operationId: String): List<GitHubRelease> {
        val url = apiRoot.newBuilder().addPathSegments("repos/Buckeyes22/collie/releases")
            .addQueryParameter("per_page", "100").build()
        val bytes = executeBytes(url, MAX_RELEASE_FEED_BYTES, operationId, apiHeaders = true)
        return json.decodeFromString(bytes.decodeToString(throwOnInvalidSequence = true))
    }

    private suspend fun verifyRelease(release: GitHubRelease, operationId: String): AndroidReleaseCandidate? {
        val assetsByName = release.assets.groupBy(GitHubAsset::name)
        val tagVersion = release.tagName.removePrefix("android-v")
        val apkName = "collie-android-$tagVersion.apk"
        val apk = assetsByName[apkName]?.singleOrNull() ?: return null
        val metadataAsset = assetsByName["release-metadata.json"]?.singleOrNull() ?: return null
        val signatureAsset = assetsByName["release-metadata.sig"]?.singleOrNull() ?: return null
        if (apk.size !in 1..AndroidReleaseMetadataVerifier.MAX_APK_BYTES) return null
        if (metadataAsset.size !in 1..AndroidReleaseMetadataVerifier.MAX_METADATA_BYTES.toLong()) return null
        if (signatureAsset.size !in 1..AndroidReleaseMetadataVerifier.MAX_SIGNATURE_TEXT_BYTES.toLong()) return null
        val apkUrl = releaseAssetUrl(apk, release.tagName) ?: return null
        val metadataUrl = releaseAssetUrl(metadataAsset, release.tagName) ?: return null
        val signatureUrl = releaseAssetUrl(signatureAsset, release.tagName) ?: return null
        val rawMetadata = executeBytes(metadataUrl, AndroidReleaseMetadataVerifier.MAX_METADATA_BYTES, operationId)
        val rawSignature = executeBytes(signatureUrl, AndroidReleaseMetadataVerifier.MAX_SIGNATURE_TEXT_BYTES, operationId)
        val verification = verifier.verify(rawMetadata, rawSignature, 0L, Build.VERSION.SDK_INT)
        val verified = (verification as? ReleaseMetadataResult.Verified)?.release ?: return null
        val metadata = verified.metadata
        if (metadata.tag != release.tagName || metadata.apk != apk.name || metadata.apkSizeBytes != apk.size) return null
        if (metadata.applicationId != PRODUCTION_PACKAGE || metadata.versionName != tagVersion) return null
        if (metadata.applicationId != packageName) return null
        return AndroidReleaseCandidate(
            tag = metadata.tag,
            versionName = metadata.versionName,
            versionCode = metadata.versionCode,
            notes = release.body.take(MAX_RELEASE_NOTES_CHARS),
            sourceUrl = "https://github.com/Buckeyes22/collie/releases/tag/${metadata.tag}",
            byteSize = metadata.apkSizeBytes,
            metadata = verified,
            manifestDigest = sha256(rawMetadata),
            acceptedRevision = 0L,
            installedVersionCode = 0L,
            installedSignerDigests = emptyList(),
            apkUrl = apkUrl,
            metadataUrl = metadataUrl,
            signatureUrl = signatureUrl,
        )
    }

    private fun releaseAssetUrl(asset: GitHubAsset, tag: String): HttpUrl? {
        val parsed = asset.browserDownloadUrl.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.host != releaseHost || parsed.port != allowedPort) return null
        if (parsed.encodedPath != "/Buckeyes22/collie/releases/download/$tag/${asset.name}") return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) return null
        return parsed
    }

    private suspend fun fetchApk(
        candidate: AndroidReleaseCandidate,
        part: File,
        operationId: String,
        reportProgress: suspend (Long, Long) -> Unit,
    ) {
        var offset = part.length().coerceAtMost(candidate.byteSize)
        if (offset == candidate.byteSize) return
        currentCoroutineContext().ensureActive()
        val request = Request.Builder().url(candidate.apkUrl).get().header("Accept", "application/octet-stream")
            .apply { if (offset > 0L) header("Range", "bytes=$offset-") }.build()
        val response = executeFollowingRedirects(request, apiHeaders = false, operationId = operationId)
        try {
            val current = response.response
            if (offset > 0L && current.code == 200) {
                // A server may ignore Range; restart cleanly rather than append a full response.
                offset = 0L
                part.delete()
            } else if (current.code != 206 && current.code != 200) {
                throw ReleaseRequestException()
            }
            if (current.code == 206) {
                val match = CONTENT_RANGE.matchEntire(current.header("Content-Range") ?: "") ?: throw ReleaseRequestException()
                val rangeStart = match.groupValues[1].toLongOrNull() ?: throw ReleaseRequestException()
                val rangeEnd = match.groupValues[2].toLongOrNull() ?: throw ReleaseRequestException()
                val fullSize = match.groupValues[3].toLongOrNull() ?: throw ReleaseRequestException()
                if (rangeStart != offset || fullSize != candidate.byteSize || rangeEnd < rangeStart || rangeEnd != candidate.byteSize - 1L) {
                    throw ReleaseRequestException()
                }
            }
            val body = current.body ?: throw ReleaseRequestException()
            val declared = body.contentLength()
            if (declared > candidate.byteSize - offset) throw ReleaseRequestException()
            val out = try { FileOutputStream(part, offset > 0L) } catch (write: IOException) { throw StorageWriteException(write) }
            val source: BufferedSource = body.source()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = offset
            var transferFailure: Throwable? = null
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    downloadingWitnesses[operationId]?.let(::ensureCurrentOperation)
                    val read = source.read(buffer, 0, buffer.size)
                    downloadingWitnesses[operationId]?.let(::ensureCurrentOperation)
                    if (read < 0) break
                    total += read
                    if (total > candidate.byteSize || total > AndroidReleaseMetadataVerifier.MAX_APK_BYTES) throw ReleaseRequestException()
                    try { out.write(buffer, 0, read) } catch (write: IOException) { throw StorageWriteException(write) }
                    reportProgress(total, candidate.byteSize)
                }
                try { out.fd.sync() } catch (write: IOException) { throw StorageWriteException(write) }
            } catch (failure: Throwable) {
                transferFailure = failure
                throw failure
            } finally {
                try { out.close() } catch (write: IOException) { if (transferFailure == null) throw StorageWriteException(write) }
            }
            if (total != candidate.byteSize || part.length() != candidate.byteSize) throw IOException("Incomplete APK response")
        } finally {
            response.close()
        }
    }

    private suspend fun executeBytes(url: HttpUrl, maximum: Int, operationId: String, apiHeaders: Boolean = false): ByteArray {
        val request = Request.Builder().url(url).get().apply {
            if (apiHeaders) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                header("User-Agent", USER_AGENT)
            }
        }.build()
        val response = executeFollowingRedirects(request, apiHeaders = apiHeaders, operationId = operationId)
        try {
            val current = response.response
            if (!current.isSuccessful) throw ReleaseRequestException()
            val body = current.body ?: throw ReleaseRequestException()
            if (body.contentLength() > maximum) throw ReleaseRequestException()
            val source = body.source()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (out.size() + read > maximum) throw ReleaseRequestException()
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        } finally {
            response.close()
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun executeFollowingRedirects(initial: Request, apiHeaders: Boolean, operationId: String): HeldResponse {
        var request = initial
        repeat(MAX_REDIRECTS + 1) { hop ->
            val url = request.url
            if (!allowedUrl(url, apiHeaders, hop == 0)) throw ReleaseRequestException()
            if (apiHeaders && hop > 0) throw ReleaseRequestException()
            val call = client.newCall(request)
            activeCalls[operationId] = call
            OWNED_CALLS[call] = preferences to operationId
            // A supersession can precede registration; check the ORIGINAL witness after registration.
            downloadingWitnesses[operationId]?.let { witness ->
                try { ensureCurrentOperation(witness) } catch (retired: StaleOperationException) {
                    activeCalls.remove(operationId, call); OWNED_CALLS.remove(call); call.cancel(); throw retired
                }
            }
            val cancellationHandle = registerCancellation(call)
            val response = try { call.execute() } catch (failure: Throwable) {
                releaseCall(operationId, call, cancellationHandle)
                // TLS/DNS/socket refusal is transport failure. Keep JSON/signature/archive
                // parsing outside this conversion so hostile bytes never become NETWORK.
                throw if (failure is IOException) ReleaseRequestException(failure) else failure
            }
            if (response.code !in REDIRECT_CODES) {
                if (hop == 0 && apiHeaders && url.host != API_HOST) {
                    response.close()
                    releaseCall(operationId, call, cancellationHandle)
                    throw ReleaseRequestException()
                }
                return HeldResponse(response, operationId, call, cancellationHandle)
            }
            val location = response.header("Location")
            val next = location?.let(url::resolve)
            response.close()
            releaseCall(operationId, call, cancellationHandle)
            if (next == null || hop == MAX_REDIRECTS || !allowedUrl(next, apiHeaders, false)) throw ReleaseRequestException()
            request = request.newBuilder().url(next).build()
        }
        throw ReleaseRequestException()
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun registerCancellation(call: Call): DisposableHandle {
        val job = currentCoroutineContext()[Job] ?: return DisposableHandle {}
        return job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) call.cancel()
        }
    }

    private fun releaseCall(operationId: String, call: Call, handle: DisposableHandle) {
        handle.dispose()
        activeCalls.remove(operationId, call)
        OWNED_CALLS.remove(call)
    }

    private inner class HeldResponse(
        val response: Response,
        private val operationId: String,
        private val call: Call,
        private val cancellationHandle: DisposableHandle,
    ) : AutoCloseable {
        override fun close() {
            try { response.close() } finally { releaseCall(operationId, call, cancellationHandle) }
        }
    }

    private fun allowedUrl(url: HttpUrl, apiHeaders: Boolean, firstHop: Boolean): Boolean {
        if (url.scheme != "https" || url.port != allowedPort || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (firstHop && apiHeaders) return url.host == apiHost && url.encodedPath == "/repos/Buckeyes22/collie/releases"
        return url.host in releaseHosts
    }

    private fun archiveMatches(metadata: AndroidReleaseMetadata, apk: File): Boolean {
        val info = packageInfo(apk) ?: return false
        if (info.packageName != PRODUCTION_PACKAGE || info.packageName != packageName) return false
        @Suppress("DEPRECATION")
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        if (archiveVersion != metadata.versionCode) return false
        if (info.applicationInfo?.minSdkVersion != metadata.minSdk) return false
        val archiveSigners = signerDigests(info)
        if (archiveSigners.size != 1 || archiveSigners.single() != metadata.signingCertificateSha256) return false
        val installedSigners = installedSignerDigests() ?: return false
        if (installedSigners.isEmpty()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return false
            val lineage = signingInfo.signingCertificateHistory.map { sha256(it.toByteArray()) }.toSet()
            installedSigners.all { it in lineage }
        } else {
            installedSigners == archiveSigners
        }
    }

    private fun installedSignerDigests(): Set<String>? = installedSignerDigestsOverrideForTests?.invoke() ?: try {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val installed = appContext.packageManager.getPackageInfo(PRODUCTION_PACKAGE, flags)
        signerDigests(installed).takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        // A sideload updater cannot update an app whose installed identity cannot be established.
        null
    }

    private fun installedPackageVersionCode(): Long? = try {
        @Suppress("DEPRECATION")
        val info = appContext.packageManager.getPackageInfo(PRODUCTION_PACKAGE, 0)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    } catch (_: Exception) { null }

    private fun packageInfo(apk: File): PackageInfo? = try {
        if (archivePackageInfoForTests != null) {
            archivePackageInfoForTests.invoke(apk)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            }
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        }
    } catch (_: Exception) {
        null
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) return emptySet()
            signing.apkContentsSigners.toList()
        } else {
            @Suppress("DEPRECATION") info.signatures?.toList().orEmpty()
        }
        return signatures.map { signature -> sha256(signature.toByteArray()) }.toSet()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.removePrefix("android-v").split('.').map(String::toInt)
        val b = right.removePrefix("android-v").split('.').map(String::toInt)
        for (index in 0..2) {
            val compare = a[index].compareTo(b[index])
            if (compare != 0) return compare
        }
        return 0
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val draft: Boolean,
        val prerelease: Boolean,
        val body: String = "",
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        val size: Long,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
    )

    private class ReleaseRequestException(cause: IOException? = null) : IOException(cause)
    private class StaleOperationException : IOException()
    private class StorageWriteException(cause: IOException) : IOException(cause)

    companion object {
        private const val API_HOST = "api.github.com"
        private const val USER_AGENT = "Collie-Android-Release-Check"
        private const val MAX_RELEASE_FEED_BYTES = 4 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val MAX_RELEASE_NOTES_CHARS = 16_000
        private const val MIN_STAGING_RESERVE_BYTES = 8L * 1024L * 1024L
        private const val PRODUCTION_PACKAGE = "com.lateapex.collie"
        private const val RELEASE_PREFS = "android_release_trust"
        private const val KEY_ACCEPTED_VERSION = "accepted_version_code"
        private const val KEY_ACCEPTED_DIGEST = "accepted_manifest_sha256"
        private const val KEY_ACCEPTED_CHAIN = "accepted_key_chain"
        private const val KEY_ACCEPTED_KEY_ID = "accepted_metadata_key_id"
        private const val KEY_ACCEPTED_ACTIVATION = "accepted_activation_version_code"
        private const val KEY_ACCEPTED_REVISION = "accepted_revision"
        private const val KEY_OPERATION = "active_release_operation"
        private const val KEY_INSTALL_ATTEMPT = "install_attempt"
        private const val OP_CREATED = "created"
        private const val OP_DOWNLOADING = "downloading"
        private const val OP_READY = "ready"
        private const val OP_FAILED = "failed"
        private const val OP_CANCELED = "canceled"
        private const val OP_INSTALL_PREPARED = "installPrepared"
        private const val OP_INSTALL_DISPATCHED = "installDispatched"
        private const val OP_INSTALL_PENDING = "installPending"
        private const val OP_INSTALLED = "installed"
        private const val OP_REFUSED = "refused"
        private const val OP_UNKNOWN = "unknown"
        private val ACTIVE_OPERATION_STATES = setOf(OP_CREATED, OP_DOWNLOADING, OP_INSTALL_PREPARED, OP_INSTALL_DISPATCHED, OP_INSTALL_PENDING, OP_UNKNOWN)
        private val OPERATION_STATES = ACTIVE_OPERATION_STATES + setOf(OP_READY, OP_FAILED, OP_CANCELED, OP_INSTALLED, OP_REFUSED)
        private const val SESSION_PROTOCOL = "packageInstallerV1"
        private const val INSTALL_PREPARED = "prepared"
        private const val INSTALL_DISPATCHED = "dispatched"
        private const val INSTALL_PENDING = "pending"
        private const val INSTALL_INSTALLED = "installed"
        private const val INSTALL_CANCELED = "canceled"
        private const val INSTALL_REFUSED = "refused"
        private const val INSTALL_UNKNOWN = "unknown"
        private const val REFUSAL_STALE = "staleOperation"
        private const val REFUSAL_TRUST = "trustRefused"
        private const val REFUSAL_FILE = "fileRefused"
        private const val REFUSAL_INSTALLED_IDENTITY = "installedIdentityUnavailable"
        private const val REFUSAL_INSTALLED_CHANGED = "installedIdentityChanged"
        private const val REFUSAL_INSTALL_PENDING = "installAlreadyPending"
        private const val REFUSAL_PERSISTENCE = "durableReservationFailed"
        private val INSTALL_PHASES = setOf(INSTALL_PREPARED, INSTALL_DISPATCHED, INSTALL_PENDING, INSTALL_INSTALLED, INSTALL_CANCELED, INSTALL_REFUSED, INSTALL_UNKNOWN)
        private val BLOCKING_INSTALL_PHASES = setOf(INSTALL_PREPARED, INSTALL_DISPATCHED, INSTALL_PENDING, INSTALL_UNKNOWN)
        private val OPERATION_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
        private val TRUST_STATE_LOCK = Any()
        private val OWNED_CALLS = java.util.concurrent.ConcurrentHashMap<Call, Pair<SharedPreferences, String>>()
        private val STABLE_TAG = Regex("android-v[0-9]+\\.[0-9]+\\.[0-9]+")
        private val RELEASE_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val RELEASES_API = "https://api.github.com".toHttpUrl()

        private fun decodeStrictBase64Url(value: String): ByteArray {
            require(value.isNotEmpty() && value.matches(Regex("[A-Za-z0-9_-]+")))
            val decoded = Base64.getUrlDecoder().decode(value)
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value)
            return decoded
        }

        private fun anonymousClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .callTimeout(java.time.Duration.ofMinutes(2))
            .build()
    }
}
