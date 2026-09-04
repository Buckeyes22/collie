# Collie for Android

This directory contains the Android shell for Collie. It is a Trusted Web Activity (TWA): Android
launches the live PWA at `https://ed8.taile7b6b1.ts.net/` in a supporting browser. It does not
bundle the React application, use a WebView, or expose a JavaScript/native bridge.

The Android package is `com.lateapex.collie`. Browser-owned origin storage remains authoritative
for pairing, service workers, site permissions, and Web Push subscriptions. The package never reads
Collie's pairing token or terminal content.

See [`../ANDROID_TWA_SPEC.md`](../ANDROID_TWA_SPEC.md) for normative requirements and
[`../ANDROID_TWA_IMPLEMENTATION_PLAN.md`](../ANDROID_TWA_IMPLEMENTATION_PLAN.md) for the rollout and
acceptance gates.

## Pinned toolchain

The generated project was created from the live PWA manifest with these versions:

| Component | Version |
| --- | --- |
| Node.js | 22.x |
| `@bubblewrap/cli` | 1.25.0, exact pin in `package-lock.json` |
| OpenJDK | 21 locally; generated Java source/target is 17 |
| Compile SDK | 36 |
| Target SDK | 36 |
| Android build tools | 36.0.0 available locally |
| Gradle wrapper | 8.11.1 |
| Android Gradle Plugin | 8.9.1 |
| Android Browser Helper | 2.6.2 |
| AndroidX Browser | 1.9.0-alpha04, resolved transitively by Browser Helper |

The Gradle wrapper is the build entry point. A global Gradle installation is neither needed nor
supported. Bubblewrap is Android-only development tooling and must not be added to Collie's root
dependency tree.

## Install the pinned generator

From this directory:

```bash
npm ci --no-audit --no-fund
npx --no-install bubblewrap --version
```

The second command must report `1.25.0`. On first use, Bubblewrap asks for a JDK and Android SDK;
select the existing local installations. It stores those workstation-specific paths outside Git.
Never commit `local.properties` or a Bubblewrap configuration containing absolute workstation
paths. The install command deliberately avoids making reproducibility depend on npm's advisory and
funding endpoints; run dependency auditing as a separate review step.

`twa-manifest.json` is the reviewed generator input. Bubblewrap's `update` command overwrites
generated source. After any regeneration, inspect the complete diff and preserve the intentional
hardening in this tree: API 36, Java 17, Maven Central, `allowBackup=false`,
`usesCleartextTraffic=false`, and no declared `WebViewFallbackActivity`. Never accept a generated
keystore prompt.

## Debug and CI build

Point Gradle at an installed Android SDK without creating `local.properties`:

```bash
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :app:processDebugMainManifest
./gradlew :app:dependencies --configuration debugRuntimeClasspath
./gradlew :app:assembleDebug
./gradlew lint
```

The debug APK is written below `app/build/` and is ignored by Git. A debug build is suitable for
compile checks and emulator smoke tests only. Its debug certificate must not be published as the
private release's Digital Asset Link.

The reviewed merged manifest must show:

- package `com.lateapex.collie`, version code `1`, and version name `1.5.0`;
- minimum SDK 21 and target SDK 36;
- launch URL `https://ed8.taile7b6b1.ts.net/` and no additional trusted origin;
- only HTTPS navigation with cleartext traffic disabled;
- `POST_NOTIFICATIONS` plus Android/library-internal signature permissions, with no camera,
  microphone, storage, location, contacts, accessibility, overlay, or package-install permission;
- the exported launcher activity and notification `DelegationService` required by the TWA;
- the notification small-icon metadata; and
- no WebView activity, Firebase, analytics, crash reporter, or native bridge.

The shell itself does not need `INTERNET`: the selected browser performs network access. The
optional AndroidX profile installer is excluded because it contributes an otherwise-unneeded
exported receiver; it must remain absent from the merged manifest.

## Release signing

The repository contains no release key. Generate and back up the dedicated key outside the
worktree, using the stable alias `collie-release`, before producing the installed baseline. Keep its
store password and key password in the operator-controlled secret store. Do not put password values
in shell history, Gradle files, logs, issue text, or this document.

The checked-in `signingKey.path` is an ignored local placeholder. Never place the real key at that
placeholder; keeping the actual key outside the worktree prevents accidental deletion with build
cleanup. Override it for every release:

```bash
export COLLIE_KEYSTORE=/absolute/path/outside/the/repository/collie-release.keystore
read -rs -p 'Keystore password: ' BUBBLEWRAP_KEYSTORE_PASSWORD; echo
read -rs -p 'Key password: ' BUBBLEWRAP_KEY_PASSWORD; echo
export BUBBLEWRAP_KEYSTORE_PASSWORD BUBBLEWRAP_KEY_PASSWORD
npx --no-install bubblewrap build \
  --signingKeyPath="$COLLIE_KEYSTORE" \
  --signingKeyAlias=collie-release
unset BUBBLEWRAP_KEYSTORE_PASSWORD BUBBLEWRAP_KEY_PASSWORD
```

Do not run a release build until the operator has confirmed the external key path and recoverable
backup. Bubblewrap's APK and AAB outputs are ignored and must remain untracked.

## Digital Asset Links

Fullscreen TWA behavior requires the live origin to associate this package with the certificate
that signs the installed APK. A missing or mismatched association deliberately falls back to a
Custom Tab with browser chrome.

Obtain the public SHA-256 fingerprint without exposing the private key or password:

```bash
keytool -list -v -keystore "$COLLIE_KEYSTORE" -alias collie-release
```

Use a disposable manifest copy to generate the statement. This keeps the tracked generator input
and its checksum stable; mutating the tracked manifest would make a later Bubblewrap build offer to
regenerate and overwrite the reviewed Android hardening.

```bash
cp twa-manifest.json .local/twa-manifest.release.json
npx --no-install bubblewrap fingerprint add \
  'AA:BB:CC:REPLACE_WITH_THE_COMPLETE_SHA256' \
  --name=private-release \
  --manifest="$PWD/.local/twa-manifest.release.json" \
  --output="$PWD/.local/assetlinks.generated.json"
jq . .local/assetlinks.generated.json
```

Copy only the reviewed public statement to
`../web/public/.well-known/assetlinks.json`. Never deploy the example fingerprint above. Collie's
normal web build must copy it byte-for-byte to `web/dist/.well-known/assetlinks.json`.

From the repository root, build the web application and run the release-strict identity gate before
deploying the association or installing a signed APK:

```bash
bun run build
bun run check:android:release
```

The strict check must fail while the Digital Asset Links source is absent, malformed, contains a
placeholder, differs from the built copy, or names a different package or certificate format.

Before installing a release, compare all three public identities:

1. `keytool -list -v` for the external keystore;
2. API 36 `apksigner verify --verbose --print-certs <apk>` for the final APK; and
3. `sha256_cert_fingerprints` in the built and live `assetlinks.json`.

They must resolve to the same uppercase, colon-separated SHA-256 fingerprint. Also verify the APK's
package and version:

```bash
apkanalyzer manifest application-id /path/to/collie-release.apk
apkanalyzer manifest version-code /path/to/collie-release.apk
apkanalyzer manifest version-name /path/to/collie-release.apk
apksigner verify --verbose --print-certs /path/to/collie-release.apk
sha256sum /path/to/collie-release.apk
```

Verify the deployed association from a tailnet-connected machine without following redirects:

```bash
curl -fsS -D .local/assetlinks.headers \
  -o .local/assetlinks.live.json \
  https://ed8.taile7b6b1.ts.net/.well-known/assetlinks.json
jq . .local/assetlinks.live.json
```

The response must be a redirect-free `200` with `Content-Type: application/json`. Do not create a
public Funnel or require a Collie pairing credential for this static file.

## Install and verify

Use the SDK's `adb`; its directory need not be placed permanently on `PATH`:

```bash
/path/to/Android/Sdk/platform-tools/adb devices
/path/to/Android/Sdk/platform-tools/adb install /path/to/collie-release.apk
```

For an update, first verify that package and signer continuity match the installed application and
that `versionCode` increased. Only then use:

```bash
/path/to/Android/Sdk/platform-tools/adb install -r /path/to/collie-release.apk
```

Useful relationship-verification diagnostics are:

```bash
/path/to/Android/Sdk/platform-tools/adb shell pm get-app-links com.lateapex.collie
/path/to/Android/Sdk/platform-tools/adb shell am start \
  -a android.intent.action.VIEW \
  -d https://ed8.taile7b6b1.ts.net/
/path/to/Android/Sdk/platform-tools/adb logcat \
  | grep -E 'OriginVerifier|TrustedWebActivity|DigitalAssetLinks'
```

The release gate is visual and behavioral as well as diagnostic: the verified origin opens without
browser chrome, off-origin navigation does not inherit trusted fullscreen treatment, the icon and
dark splash render correctly, and the notification icon is legible in light and dark system themes.

## Pairing and notifications

The selected TWA browser owns Collie's origin storage. If its profile already visited the origin,
the existing `collie:device-token` may be shared; otherwise the Android launch is a new device and
must use the normal `collie pair` flow. Do not copy a token into Android storage. Clearing browser
site data or switching the TWA provider can require pairing and notification subscription again.

Notification delegation is enabled and uses Collie's existing VAPID/Web Push subscription. On
Android 13 and later, denying the app notification permission is supported; re-enable it in Android
App Info and then use Collie's Settings page if needed. Recovery must not introduce Firebase or a
parallel native subscription.

After pairing, exercise foreground, background, and cold-start delivery with Collie's existing
push-test command. Confirm that Android attributes the notification to Collie and that pane and
update deep links open the expected in-scope route without browser chrome. Check the Collie push
subscription list before and after disable/re-enable to catch orphan subscriptions.

## Updates and rollback

A web-only Collie deployment does not require a new APK: the TWA renders the live origin, and the
existing service worker controls web rollout. Rebuild the APK only when Android resources,
configuration, dependencies, package metadata, or signing identity change. Increase `versionCode`
for every APK that may update an installed build; keep `versionName` aligned with the source release
used for that shell.

An APK rollback is possible only when Android accepts its package, signer, and version ordering. In
practice, restore service quickly with a web rollback first. Installing a lower-version APK usually
requires uninstalling the package, which can discard package-level notification state. Losing the
release key or changing the application ID also requires uninstall/reinstall; neither is a routine
recovery path.

Keep the last known-good signed APK, checksum, source commit, version metadata, and public signer
fingerprint in an operator-controlled release record outside Git. Never include terminal content,
pairing tokens, passwords, or private key material in that record.
