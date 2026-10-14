package com.lateapex.collie.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import java.io.File
import java.security.MessageDigest
import org.robolectric.Shadows

/**
 * Simulated PackageManager boundary, not archive-parser evidence. These component-free TEST-only archives
 * were verified with SDK37 aapt2 and apksigner. They use a disposable test signer;
 * they are never release APKs or production-signer evidence. The JDK/Robolectric archive primitive cannot provide their
 * signing metadata. Unknown or changed bytes never receive simulated identity.
 * Real Android cache-file parsing is still required; these fixtures must never install.
 */
internal object VerifiedPublicArchiveFixture {
    private data class Identity(val packageName: String, val version: Int, val name: String)
    private val identities = mapOf(
        "1fc0ea1b2a0e15fa4256362d71a7de3500dff8bf6e213e38f18d59f8720ffba5" to
            Identity("com.lateapex.collie", 100000, "1.5.3"),
        "2459be892f62b8c534586b831098d7a9a9fc8a99408c4ef3a07cc5ba413ed1bc" to
            Identity("com.lateapex.collie", 100001, "1.5.4"),
        "bea932875d9d0a4c7fc752698c3aea5ff3df92511c79b38edebe8dae90b0de68" to
            Identity("com.lateapex.collie.wrongfixture", 100000, "1.5.3"),
    )

    @Suppress("DEPRECATION")
    fun read(file: File): PackageInfo? {
        if (!file.isFile || file.length() != 8530L) return null
        val identity = identities[sha256(file.readBytes())] ?: return null
        val certificate = requireNotNull(javaClass.classLoader?.getResourceAsStream("android-release-public-fixture.cer"))
            .use { it.readBytes() }
        check(sha256(certificate) == "04cc6cede086ffbfcd6aa50c7a52eb13705bb6fdd08e00b76c785b04bfd6400d")
        val signer = Signature(certificate)
        return PackageInfo().apply {
            packageName = identity.packageName
            versionName = identity.name
            versionCode = identity.version
            applicationInfo = ApplicationInfo().apply {
                packageName = identity.packageName
                minSdkVersion = 37
                targetSdkVersion = 37
            }
            signatures = arrayOf(signer)
            if (Build.VERSION.SDK_INT >= 28) {
                longVersionCode = identity.version.toLong()
                signingInfo = SigningInfo().also {
                    Shadows.shadowOf(it).setSignatures(arrayOf(signer))
                    Shadows.shadowOf(it).setPastSigningCertificates(arrayOf(signer))
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
