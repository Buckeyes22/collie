# Releasing Collie for Android

Prepare a signed APK, pass the build checks and smoke-test the artifact before publishing.

## Release identity

Android app releases are independent of server/PWA releases.

| Field | Current development value | Release rule |
| --- | --- | --- |
| Application ID | `com.lateapex.collie` | Preserve for in-place upgrades. |
| Debug application ID | `com.lateapex.collie.debug` | Development only; separate local data. |
| versionName | `1.5.2` | Pick the next Android SemVer at release time. |
| versionCode | `3` | Increase for every distributed update. |
| Tag | `android-vX.Y.Z` | Must match the APK's versionName. |
| Changelog | `ANDROID_CHANGELOG.md` | Record app changes separately from the server. |

The version lives in `android/app/build.gradle.kts`. The native checker and its fixtures pin
that version in `scripts/check-android-native.ts` and `scripts/check-android-native.test.ts`;
update those pins in the same release commit. Leave `herdr-plugin.toml`, the two package versions
and the server release headings unchanged for an Android-only release.

Keep the signing key and application ID consistent. Changing either requires an explicit
migration; losing a signing key can prevent existing users from updating.

## Configure signing

Choose a production signing key and back it up outside this repository before distribution.

Do not use an Android debug keystore. If creating a new key, use Android's
[app-signing guidance](https://developer.android.com/studio/publish/app-signing) and keep an
encrypted backup. Existing production keys must be reused for upgrades. The signing helper
does not create keys or guess which key is correct.

For local signing, supply these environment variables through your private secret manager or
interactive shell prompts. Never commit their values or pass passwords directly on the command line.

| Variable | Purpose |
| --- | --- |
| `ANDROID_HOME` | SDK with Build Tools 36.0.0 |
| `COLLIE_ANDROID_KEYSTORE` | Absolute path to the release keystore outside the worktree |
| `COLLIE_ANDROID_KEY_ALIAS` | Alias of the private key |
| `COLLIE_ANDROID_STORE_PASSWORD` | Keystore password |
| `COLLIE_ANDROID_KEY_PASSWORD` | Private-key password |
| `COLLIE_ANDROID_CERT_SHA256` | Independently checked certificate SHA-256 fingerprint |
| `COLLIE_ANDROID_EXPECTED_TAG` | Optional expected `android-vX.Y.Z` tag |
| `COLLIE_ANDROID_RELEASE_DIR` | Optional fresh output directory; existing output is refused |

For GitHub Actions, configure an `android-release` environment restricted to trusted release
tags. Store the alias, two passwords and fingerprint as environment secrets with the same names.
Add `COLLIE_ANDROID_KEYSTORE_BASE64` containing the keystore's base64 encoding. The workflow
decodes it into runner-temporary storage and removes that file after signing. The fingerprint
must come from your trusted key record, not an unreviewed candidate APK.

Private vulnerability reporting is currently disabled on this fork. Enable it in repository
Settings → Security before publishing and confirm the report route in [SECURITY.md](../SECURITY.md)
works from a contributor account.

## Prepare a local candidate

Run the native gates before signing the release variant.

```bash
bun run check:android
(cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug lintRelease assembleRelease)
```

The build produces `android/app/build/outputs/apk/release/app-release-unsigned.apk`.
This unsigned APK is a signing input, not an installable public release.

Record what produced it, on the clean release commit and straight after the build:

```bash
bash scripts/android-build-manifest.sh
```

This writes `build-inputs.json` beside the APK: the commit, the `gradle.lockfile` hash and the
unsigned APK hash. It refuses a dirty tree.

With signing variables loaded, run from the repository root:

```bash
bash scripts/android-release.sh
```

The helper checks the build-inputs manifest against the checkout and the APK, and refuses a dirty
tree. It also checks package identity, non-debuggable status, version syntax and the expected tag,
then signs and verifies the APK against the pinned certificate. It passes passwords through
apksigner's environment-variable inputs. Android documents those options in
[apksigner](https://developer.android.com/tools/apksigner).

Output goes to `android/app/build/release-dist/`, including the APK, `SHA256SUMS`, public signer
details, source/version metadata, the MIT license and bundled asset notices. Verify it:

```bash
(cd android/app/build/release-dist && sha256sum -c SHA256SUMS)
```

The metadata embeds the verified build inputs (`buildInputsVerified`). Public candidates must come
from the clean release commit. Preserve the R8 mapping from `android/app/build/outputs/mapping/release/mapping.txt`
in private release evidence for crash investigation.

## Dependency inventory and vulnerability scan

The signing helper requires Bun and writes `sbom.cdx.json` beside the signed APK. Its CycloneDX
1.6 inventory is generated from the Gradle lockfile and includes locked `releaseRuntimeClasspath`
modules, including transitive and platform modules. It excludes build and test dependencies. The
root component's SHA-256 is the actual signed APK hash, so the inventory is tied to those exact APK
bytes.

This is a build-input inventory, not an inventory of code retained after R8. It does not claim to
identify bundled assets or dependency edges, and third-party license data is not inferred from the
lockfile. Those omissions are marked incomplete in the SBOM; review the packaged notices separately.
Rebuild the APK from the current source and lockfile before signing. The APK hash links the inventory
to an artifact; it does not prove that an older APK was built with the current lockfile.

The release workflow scans a CycloneDX inventory generated from the unsigned release APK. It has
the same locked dependency inventory as the signed candidate SBOM, while the signed SBOM binds the
signed APK's own hash. CI pins Grype 0.116.1 and `anchore/scan-action` v7.4.2 by full commit SHA.

The scan uses [`grype.yaml`](grype.yaml), requires a vulnerability database built within 120 hours,
and fails at high or critical severity, including findings without a known fix. An empty ignore list
keeps blanket suppressions out of the scan. A stale or unavailable database is a failed scan, not a
clean result, and the signing job runs only after the build and scan job succeeds.

To scan a locally signed candidate from the repository root, first confirm Grype 0.116.1 is
installed. This command writes the JSON report before you review it; a high or critical finding can
make Grype return nonzero while leaving the report available:

```bash
grype sbom:android/app/build/release-dist/sbom.cdx.json \
  --config android/grype.yaml --fail-on high -o json \
  --file android/app/build/release-dist/grype.json
```

Review the JSON report and database status before treating a local scan as complete. Grype uses a
local vulnerability database and updates it when possible; its age check must pass for the scan to
be current. See Anchore's [vulnerability database guidance](https://oss.anchore.com/docs/guides/vulnerability/database/)
and the [scan-action documentation](https://github.com/anchore/scan-action) for tool behavior.

The workflow uploads generated dependency evidence in the `android-dependencies` Actions artifact,
including the SBOM and JSON report when those files were produced, even when findings fail the scan
gate. Archive that artifact with the release review record before its Actions retention period ends.
Keep the signed candidate's `sbom.cdx.json`, `grype.json`, APK checksum, signer fingerprint, source
commit, Grype version, database build time, scan result, tag and workflow-run URL together. Do not
record a dependency audit as passed when the report is missing, Grype could not obtain a current
database, or the scan step failed.

## Device acceptance

Smoke-test the signed artifact before publication; keep the extended matrix as follow-up coverage.

Use a dedicated test device or emulator and synthetic terminal content. Do not run uninstalling
instrumentation against a personal phone as a routine release check: the runner may reinstall the
app and erase its local connection. The existing API 26/API 36 emulator checks provide broad
regression coverage; they do not need to be repeated on every physical-device configuration before
an initial release.

Initial artifact smoke check:

- [ ] APK installs and launches; packaged version and signer match the intended release.
- [ ] Setup opens with an empty server field and basic connection/read-only behavior works.
- [ ] Checksums, bundled license notices and release notes match the reviewed artifact.
- [ ] Public screenshots and attachments contain no unapproved private content.

Extended follow-up matrix:

- Fresh HTTPS pairing, authorization refusal, device revocation and harmless exactly-once replies.
- Prompts, Keys, history, attachments and configured STT against a disposable live server.
- Background/foreground, real network/VPN loss, server restart and process-death recovery.
- Diagnostics opt-in and pairing-body exclusion on a real deployment.
- Additional IMEs, insets, spoken TalkBack, large text, split window and both themes.
- Physical battery/CPU/network/wakeup measurements and upgrade continuity.

Record which checks actually ran, with APK checksum, certificate fingerprint, source commit,
app/server versions, device/API and date. Disclose material known limitations in release notes.
Untested extended scenarios are coverage gaps; an observed regression affecting basic use, credential
protection or terminal write safety requires a fix. Historical
[acceptance records](README.md#emulator-and-physical-device-acceptance) remain regression evidence.

## Cut and review a release

Only tag the clean commit after choosing the Android version and completing the gates.

1. Update versionName, versionCode and checker pins in one release commit.
2. Move the Android Unreleased entries into a dated version section.
3. Add a fresh empty Unreleased section above it.
4. Run the checks and commit the release files.
5. Create an annotated `android-vX.Y.Z` tag on that commit.
6. Push the commit and tag when ready to create the draft.

For example, after a release commit for Android 1.5.2:

```bash
git tag -a android-v1.5.2 -m "Collie for Android 1.5.2"
git push origin main android-v1.5.2
```

The [Android release workflow](../.github/workflows/android-release.yml) tests and builds before
the signing job can access release secrets. Signing checks the tag against the packaged version
and verifies the certificate. It creates a **draft** GitHub Release with signed assets and setup
links. Missing secrets or a signer mismatch fail the job; there is no unsigned fallback.

Review the draft notes, version/code, source commit, checksum, certificate, notices and artifact
smoke-check result. Record extended validation gaps accurately; completing the entire follow-up
matrix is not required to publish the initial release. The workflow does not publish
automatically. GitHub CLI documents the draft behavior in
[gh release create](https://cli.github.com/manual/gh_release_create).

The inherited `vX.Y.Z` server workflow is restricted to upstream. Its tarballs and website
dispatch do not participate in an Android release. Play Store and F-Droid publication are not
configured; they require their own signing and distribution work.

## Failed signing or recovery

Fix the stated error before reusing a release candidate.

| Failure | Remedy |
| --- | --- |
| Missing signing variable | Load the named secret; do not add it to source. |
| Certificate mismatch | Stop and compare against the backed-up production signer. |
| Tag/version mismatch | Correct the release commit or tag before distributing. |
| Existing output directory | Choose a fresh `COLLIE_ANDROID_RELEASE_DIR` to preserve earlier evidence. |
| Published app regression | Ship a fixed APK with a higher versionCode and the same signer. |

Do not replace a published APK with different bytes under the same version. Keep the previous
release available for investigation and communicate recovery steps without asking users to
discard connection state unnecessarily.
