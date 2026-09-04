# Android Trusted Web Activity — implementation specification

**Status:** Accepted (2026-09-04)

**Specification date:** 2026-09-04

**Architecture decision:** [ADR 0035 — The Android app is a TWA, not a WebView](./.adr/0035-the-android-app-is-a-twa-not-a-webview.md)

**Source baseline:** Collie `v1.5.0`, commit `2eff683d74511398923d4cb5a5ee7ac4f758ff32`

**Repository intake:** Forked to `https://github.com/Buckeyes22/collie`; the working branch starts
at the baseline above, `origin` names the fork, and the original `AltanS/collie` remote is retained
as fetch-only `upstream`

**First delivery:** Private, release-signed APK sideloaded onto the operator's Samsung S25 Ultra

This document specifies how this fork will add an Android application without replacing Collie's
web application. The first Android release is a thin
[Trusted Web Activity (TWA)](https://developer.chrome.com/docs/android/trusted-web-activity)
generated with Bubblewrap. It opens the fork's existing HTTPS PWA in a supporting Android browser,
uses Digital Asset Links to remove browser chrome, and delegates Web Push notifications to the
Android package.

The words **MUST**, **MUST NOT**, **SHOULD**, and **MAY** are normative.

---

## 1. Decision and intended outcome

The fork MUST add a TWA Android shell. It MUST NOT replace the React PWA with a native UI, package
the PWA in a WebView, or add a Capacitor bridge in the first release.

The result is complete when a release-signed APK installed on the S25 Ultra:

1. launches `https://ed8.taile7b6b1.ts.net/` without browser chrome;
2. renders the same Collie UI and uses the same same-origin API as the browser-installed PWA;
3. retains or can re-establish the existing pairing credential in browser-owned origin storage;
4. receives the existing Web Push notifications under the Android application's identity;
5. opens notification deep links at the intended Collie route;
6. preserves the existing PWA's offline app-shell behavior; and
7. introduces no native JavaScript bridge, Firebase dependency, or new public network exposure.

The initial artifact is a private APK. An Android App Bundle and Google Play listing are explicitly a
later distribution phase, not a condition of the first milestone.

---

## 2. Why this is the selected architecture

### 2.1 Existing capabilities the TWA reuses

The current fork baseline already supplies the web capabilities an Android shell needs:

| Capability | Existing implementation | Required Android treatment |
| --- | --- | --- |
| Installable PWA | Manifest generated in `web/vite.config.ts` | Bubblewrap consumes the deployed manifest |
| App identity | Name, description, dark colors, portrait orientation, 192 px and 512 px adaptive icons | Reuse in Android resources and TWA configuration |
| Offline shell | Custom Workbox service worker in `web/src/sw.ts` | Leave browser-owned and verify parity in the TWA |
| Pairing credential | `collie:device-token` in `localStorage`, read by `web/src/lib/pairing.ts` | Leave in origin/browser storage; never copy it into Android storage |
| Push subscription | Push API and VAPID flow in `web/src/lib/push.ts` | Reuse unchanged; enable TWA notification delegation |
| Push rendering and deep links | `push` and `notificationclick` handlers in `web/src/sw.ts` | Verify under delegated notifications, including cold starts |
| Same-origin API | The bridge serves `web/dist` and `/api/*` on one HTTPS origin | Preserve exactly; add no CORS exception for Android |
| Static JSON support | `bridge/server.ts` serves `.json` as `application/json` | Publish Digital Asset Links through the normal web build |
| License | MIT, copyright Altan Sarisin | Keep the copyright and MIT license in distributions |

The TWA host cannot read the browser's cookies, `localStorage`, or page content. That separation is
desirable here: the fork does not need a native bridge, and the Android package does not gain a
second copy of Collie's bearer credential.

### 2.2 Rejected first-release alternatives

| Approach | Estimated first implementation | Push impact | Decision |
| --- | ---: | --- | --- |
| Installed PWA only | Already available | Existing Web Push works | Remains supported, but does not provide an APK |
| TWA | Hours to one day after identifiers and signing are settled | Existing Web Push is retained and may be delegated to the app | **Selected** |
| Capacitor or custom WebView | Roughly 2–4 weeks | Requires replacement or native bridging, normally including FCM setup | Rejected for v1 |
| Native Compose UI | Roughly 6–12+ weeks | Requires rebuilding the client and notification path | Rejected as unjustified |

A bundled WebView would run the local UI under a different origin unless additional architecture
were introduced. That would force CORS and credential-handling changes into a system deliberately
built around one HTTPS origin. A Capacitor push implementation would also add a Firebase project,
`google-services.json`, a new subscription format, and native sender integration. Neither cost buys
anything required by the private APK milestone.

The WebView route would additionally expose terminal-control content to a native JavaScript bridge.
The first Android release MUST avoid that expansion of the security boundary.

---

## 3. Scope

### 3.1 In scope

- A fork-owned Android project committed under `android/`.
- Reproducible Bubblewrap configuration and Gradle wrapper.
- A release signing-key workflow in which no private key or password enters Git.
- `web/public/.well-known/assetlinks.json`, copied into `web/dist` by the normal web build.
- A verified TWA association for `https://ed8.taile7b6b1.ts.net`.
- Notification delegation through `TrustedWebActivityService`/Android Browser Helper.
- Release APK generation, signature verification, `adb` installation, and device validation.
- Documentation of configuration, building, key custody, verification, and recovery.
- Isolation of fork-specific Android changes so upstream Collie updates remain practical to merge.

### 3.2 Out of scope for the first milestone

- Google Play submission, store listing assets, review responses, or public distribution.
- Firebase Cloud Messaging, `google-services.json`, or a Firebase project.
- Capacitor, Cordova, a custom WebView, or a native JavaScript bridge.
- A native Compose rewrite or native mirrors of Collie routes.
- Native-only settings, account management, analytics, crash reporting, billing, or background jobs.
- Changes to Collie's API, pairing protocol, Web Push payload, service-worker decision logic, CORS
  posture, or Tailscale front door.
- Tailscale Funnel or any public exposure of the private Collie backend.
- Automatic migration of web state between different Android browser providers or profiles.

---

## 4. Required fixed inputs

The following values MUST be frozen before the first release key is generated. Package identity and
key identity are difficult or impossible to change without turning a later build into a different
Android application.

| Input | Required value or rule |
| --- | --- |
| Application name | `Collie` |
| Launcher name | `Collie` |
| Launch origin | `https://ed8.taile7b6b1.ts.net` |
| Start URL | `https://ed8.taile7b6b1.ts.net/` |
| Web manifest URL | `https://ed8.taile7b6b1.ts.net/manifest.webmanifest` |
| Application ID | Fork-owned reverse-DNS identifier; recommended `com.lateapex.collie` |
| Release-key alias | Stable, non-secret name; recommended `collie-release` |
| Signing-key lifetime | Long enough to outlive the application; Android release guidance requires validity beyond 2033-10-22 |
| Initial `versionCode` | `1` |
| Initial `versionName` | `1.5.0`, matching the source baseline used for the first shell |
| Target SDK | Android 16 / API 36 |
| Compile SDK | API 36 |
| Fallback | Custom Tab, never an embedded WebView |

The application ID above is a recommended default for this fork, not an assertion that its namespace
has already been registered. It MUST be confirmed before signing or external distribution. If a
different ID is selected, that one value MUST be used consistently in Gradle, the Android manifest,
the Bubblewrap manifest, Digital Asset Links, `adb` commands, and release documentation.

`versionCode` MUST increase for every APK that may update an installed build. `versionName` SHOULD
match the Collie release at the point when the Android shell is rebuilt. A web-only Collie deployment
does not require an APK rebuild: the TWA renders the live origin. The Android values therefore MUST
NOT become a fourth canonical Collie version checked by `scripts/check-version.sh`.

---

## 5. Repository layout and ownership

Implementation SHOULD produce the following shape:

```text
android/
├── README.md                     # operator build, sign, install, verify, and recovery guide
├── twa-manifest.json             # reviewed Bubblewrap source of truth
├── build.gradle                  # generated/reviewed project configuration
├── settings.gradle
├── gradle.properties
├── gradlew                       # committed Gradle wrapper
├── gradlew.bat
├── gradle/wrapper/               # committed wrapper metadata and JAR
├── app/                          # manifest, resources, and generated Android project source
└── .gitignore                    # Android-local secrets and outputs

web/public/.well-known/
└── assetlinks.json               # public certificate statements; no private material
```

Exact generated filenames MAY vary with the selected Bubblewrap release, but their responsibilities
MUST remain clear. The reviewed input (`twa-manifest.json`), wrapper, Android manifest, source, and
resources belong in Git. Generated build directories and signed artifacts do not.

The Android implementation MUST be kept under `android/` except for:

- the required Digital Asset Links file under `web/public/.well-known/`;
- narrowly scoped root build-script entries if they materially improve reproducibility; and
- documentation links.

This boundary minimizes conflicts while merging future upstream Collie releases into the fork.

Adding `web/public/.well-known/assetlinks.json` is a functional change under `web/public/`. Its
implementation commit therefore MUST add one crisp line under the appropriate `## [Unreleased]`
subheading in `CHANGELOG.md`, as required by `CLAUDE.md`. It MUST NOT bump Collie's version; version
numbers move only in a later release commit. The same rule applies if root package files or build
scripts are changed to integrate Android commands.

The durable TWA-versus-WebView choice is recorded in
[ADR 0035](./.adr/0035-the-android-app-is-a-twa-not-a-webview.md). `CLAUDE.md` carries only the
concise normative rule; this specification owns the implementation and acceptance requirements.

### 5.1 Files that MUST NOT be committed

- release or upload keystores (`*.jks`, `*.keystore`);
- signing passwords, password files, environment files, or Gradle signing properties;
- `local.properties` containing a workstation SDK path;
- `.gradle/`, any `build/` tree, APKs, AABs, mapping files, or generated signing reports;
- `node_modules/` or a global Bubblewrap installation; and
- device logs containing private Collie URLs, bearer tokens, or terminal content.

Before the Android project is committed, ignore rules MUST be tested with `git check-ignore` and a
dummy filename for every secret-bearing pattern. The dummy files MUST then be removed.

---

## 6. TWA configuration

The checked-in Bubblewrap configuration MUST express at least the following behavior:

```json
{
  "name": "Collie",
  "launcherName": "Collie",
  "packageId": "com.lateapex.collie",
  "host": "ed8.taile7b6b1.ts.net",
  "startUrl": "/",
  "scope": "/",
  "display": "standalone",
  "orientation": "portrait",
  "themeColor": "#0a0a0a",
  "backgroundColor": "#0a0a0a",
  "enableNotifications": true,
  "fallbackType": "customtabs",
  "appVersion": "1.5.0",
  "appVersionCode": 1
}
```

This is an illustrative minimum, not a byte-for-byte Bubblewrap schema fixture. The implementation
MUST generate the actual file with the pinned Bubblewrap CLI, review the generated fields, and keep
the generator's supported schema.

Additional requirements:

- The generated Android project MUST target and compile against API 36.
- The launch activity MUST only trust the configured HTTPS origin. Additional trusted origins MUST
  remain empty unless each new origin is explicitly justified and publishes its own association.
- Cleartext traffic MUST remain disabled.
- The Custom Tab fallback MUST be retained so a device with no TWA-capable provider fails visibly
  with browser chrome rather than using a WebView.
- The PWA's portrait orientation, dark splash background, and dark theme color MUST be preserved.
- The 512 px manifest tile MUST be used for the launcher and splash inputs. Adaptive/maskable output
  MUST be visually inspected on the S25 launcher.
- `web/public/badge-96x96.png` SHOULD be the source for the Android notification small icon if the
  generator accepts it. The final Android drawable MUST remain a monochrome alpha silhouette and
  MUST be inspected in both light and dark notification themes.
- The generated `TrustedWebActivityService` or Android Browser Helper delegation service MUST be
  enabled and exported exactly as required by the library. It MUST declare the small notification
  icon metadata.
- Only permissions required by the generated TWA and notification delegation may remain. In
  particular, implementation MUST review `INTERNET` and Android notification permission handling;
  unexpected camera, microphone, storage, location, contacts, or accessibility permissions are a
  release blocker. Web microphone permission, when voice input is used, remains browser-owned.
- On Android versions requiring runtime notification consent, denial MUST degrade to the existing
  Collie Settings state; it MUST NOT cause a crash or repeated prompt loop.

Bubblewrap's generated project is ordinary Android source. Generated output MUST be reviewed like
handwritten code and MUST NOT be treated as an opaque artifact that can be regenerated differently
on every workstation.

---

## 7. Digital Asset Links

### 7.1 Current blocker

As verified on 2026-09-04, this URL returns HTTP 404:

```text
https://ed8.taile7b6b1.ts.net/.well-known/assetlinks.json
```

Until it returns a valid statement for the certificate that signed the installed APK, the browser
will fall back to an ordinary Custom Tab with browser chrome. Fullscreen TWA behavior MUST NOT be
claimed based only on a successful page load.

### 7.2 Required file

The fork MUST add `web/public/.well-known/assetlinks.json`. Its release statement has this shape:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.lateapex.collie",
      "sha256_cert_fingerprints": [
        "REPLACE_WITH_COLON_SEPARATED_UPPERCASE_SHA256"
      ]
    }
  }
]
```

Requirements:

- The package name MUST exactly equal the built APK's application ID.
- The SHA-256 fingerprint MUST be taken from the certificate that signs the APK installed on the
  phone, not inferred from a filename or an upload key.
- The placeholder above MUST never be deployed.
- Debug fingerprints MUST NOT be published for the private release unless a time-bounded testing
  need is documented. If published, they MUST be a separate reviewed fingerprint and removed after
  the test period.
- Multiple legitimate release certificates MAY be listed to support a controlled key transition.
- When Play App Signing is later enabled, the Play **app-signing** certificate fingerprint MUST be
  added before Play-signed installs are tested. The upload certificate is not a substitute.
- The endpoint MUST return HTTP 200, with `Content-Type: application/json`, over valid HTTPS, and
  without a redirect.
- The statement MUST be reachable from the selected TWA browser on the phone while connected to the
  tailnet. Public internet reachability is neither required nor desired for this private deployment.
- No pairing token or authenticated write access may be required to fetch the file.

The normal Vite public-directory copy SHOULD place this file at
`web/dist/.well-known/assetlinks.json`; the normal Collie static server then serves it as JSON. The
build MUST fail or the release checklist MUST stop if either the built file or deployed response is
missing. Do not add a special API route for a static association statement.

### 7.3 Fingerprint verification

The release procedure MUST compare all three representations before installation:

1. the release keystore certificate fingerprint from `keytool`;
2. the signing certificate printed from the final APK by API 36 `apksigner`; and
3. the fingerprint deployed in `assetlinks.json`.

All three MUST match byte-for-byte after normalizing to uppercase colon-separated SHA-256. A mismatch
is a release blocker.

---

## 8. Signing and secret custody

Android requires every APK to be signed. The first private APK MUST use a dedicated release key,
not the automatically generated Android debug key.

The implementation MUST follow these rules:

1. Generate the key outside the Git worktree, with a strong unique password and the stable alias.
2. Back up the keystore and its recovery information in an operator-controlled secret store before
   relying on it for updates.
3. Never print, log, paste into an issue, or commit the key password or private key.
4. Pass signing paths and secrets at build time using an ignored local mechanism supported by the
   chosen Bubblewrap/Gradle setup.
5. Verify the final APK's signer after the build; do not assume the requested key was used.
6. Keep the same application ID and signing key for every sideloaded update. Losing either continuity
   requires uninstalling the old package, which also discards package-owned notification state.

The public SHA-256 certificate fingerprint belongs in `assetlinks.json` and may be committed. The
keystore and passwords never do.

If Google Play is pursued later, Play App Signing SHOULD be used with a separate upload key. The
Digital Asset Links file must then include the certificate Google actually uses to sign delivered
APKs.

---

## 9. Browser storage, pairing, and identity behavior

The TWA renders content in the selected supporting browser. That browser owns origin storage,
service workers, permissions, cookies, and Push API subscriptions. The Android host MUST NOT attempt
to inspect or duplicate them.

Expected behavior:

- If the TWA uses the same browser profile that already visited the Collie origin, the existing
  `collie:device-token` may already be available.
- If the provider or profile differs, the TWA starts with separate origin storage and the operator
  must pair it as a new device.
- Clearing the browser's site data, changing provider, or uninstalling relevant browser state can
  remove pairing and push state even while the APK remains installed.
- The correct recovery is the existing `collie pair` flow. The Android shell MUST NOT add a second
  token-import feature.

The first device test MUST record whether the existing browser-installed PWA and the TWA share the
pairing credential on the S25 Ultra. Either result is acceptable if it follows browser-provider
behavior; silent write failure is not. A missing credential must produce Collie's existing read-only
and pairing UI.

---

## 10. Push notifications and deep links

The Android release MUST preserve the existing VAPID/Web Push implementation. It MUST NOT introduce
FCM application code, a Firebase project, `google-services.json`, or a parallel subscription API.

`enableNotifications: true` MUST configure notification delegation. With a supporting provider, the
provider forwards notifications associated with the verified TWA scope to the package's
`TrustedWebActivityService`. The notification is then attributable to Collie and governed by the
Collie app's Android notification permission.

The following behaviors MUST be verified without changing their web implementation:

- Settings can enable and disable notifications.
- `collie push-test "Collie Android test" "Delegated Web Push"` reaches the device.
- The notification is attributed to the Collie Android application, not only to Chrome.
- A visible Collie client suppresses redundant notifications as it does in the PWA.
- Replacement tags and clear/retraction pushes do not leave stale notifications.
- A tap while the TWA is open focuses or navigates the existing client.
- A tap while the app is backgrounded opens the intended route.
- A tap after the browser process/client has been discarded cold-starts successfully.
- A pane notification opens `/pane/<id>` with its existing `host` and `session` query scope.
- An update notification opens `/settings/updates`.
- A failed open leaves the notification available for a second tap, preserving the current service
  worker behavior.

If enabling the TWA creates a new Push API subscription, the existing endpoint-replacement behavior
in `web/src/lib/push.ts` remains authoritative. Tests MUST check `collie push list` for unintended
duplicate or orphan subscriptions before and after re-enabling notifications.

Notification delegation depends on a valid Digital Asset Link. Push receipt alone does not prove
that the TWA association or app attribution is correct.

---

## 11. Security requirements

Collie can type into a real terminal. The Android shell MUST preserve the repository's existing
remote-shell security posture.

- The bridge MUST continue binding to loopback and using the existing Tailscale Serve HTTPS front
  door. The Android project does not manage a tunnel.
- Tailscale Funnel MUST remain disabled.
- The TWA MUST launch only the configured HTTPS origin. Unverified origins must display browser
  chrome according to Custom Tab/TWA behavior.
- The bridge's Host validation, same-origin gate, CSP, pairing gate, optional trusted-user gate, and
  optional device-header gate MUST remain unchanged.
- No CORS broadening is permitted for the Android package.
- No native code may receive terminal text, pairing tokens, API responses, or arbitrary messages
  from the PWA.
- No analytics, telemetry, advertising SDK, crash reporter, or install beacon may be introduced.
- No cleartext network security exception may be added for production.
- Android backups SHOULD be disabled for package-owned state unless the generated shell has no
  sensitive state and the manifest makes that fact clear. Browser-owned site data is governed by the
  browser, not this flag.
- Exported Android components MUST be limited to those required for launcher, verified links, and
  TWA notification delegation, with their required intent filters and library token checks.
- Dependency versions and generated manifest changes MUST be reviewed before every Android release.

The Digital Asset Links file delegates URL handling to an Android signing identity. A compromise of
the signing key therefore affects both update trust and the web/app association. Key compromise is
a security incident; the response is to stop distribution, remove or replace the affected
fingerprint at the origin, and follow Android signing-key recovery rules for the chosen channel.

---

## 12. Build environment and reproducibility

This workstation was verified on 2026-09-04 to have:

- OpenJDK 21.0.12;
- Node.js 22.22.1;
- Bun 1.4.0;
- Android SDK platforms 34 and 36;
- Android build-tools 34.0.0, 35.0.0, and 36.0.0;
- an Android 36 Google APIs emulator image; and
- `adb` at `/home/chris/Android/Sdk/platform-tools/adb`, not on the current `PATH`.

A global `gradle` executable is absent and is not required. The generated, committed Gradle wrapper
MUST drive builds. Build instructions MUST either export the Android SDK path locally or use the
absolute `adb` path; they MUST NOT commit a workstation-specific SDK location.

The implementation SHOULD pin the Bubblewrap CLI version used to generate/update the project. A
developer MUST be able to identify all of the following from tracked files and the build guide:

- Bubblewrap version;
- Android Browser Helper/AndroidX Browser version;
- Gradle wrapper version;
- Android Gradle Plugin version;
- compile and target SDK; and
- the commands that produce unsigned and release-signed outputs.

The dependency-age policy in `bunfig.toml` applies if Bubblewrap is added as a repository Node
dependency. The implementation MUST NOT bypass that policy merely to consume a just-published CLI.

---

## 13. Implementation sequence

### Phase A — fork and identity

1. Create or identify the writable fork remote.
2. Preserve the upstream repository as a separately named remote so future upstream syncs are
   explicit.
3. Confirm the final Android application ID and release-key alias.
4. Pin the Bubblewrap version.

**Exit gate:** the application ID is final, namespace ownership is acceptable, and no release key
has been generated under a provisional identity.

### Phase B — generate and review the Android project

1. Generate `android/` from the live PWA manifest using Bubblewrap.
2. Set API 36 as compile and target SDK.
3. Set the launch URL, portrait display, colors, icons, Custom Tab fallback, and notification
   delegation specified above.
4. Commit the Gradle wrapper and reviewed Android source; exclude build outputs and secrets.
5. Inspect the merged Android manifest and dependency tree.

**Exit gate:** a debug or unsigned build compiles, no unexpected permissions/components exist, and
the generated project can be recreated or updated from tracked configuration.

### Phase C — establish signing and association

1. Generate and back up the release keystore outside the worktree.
2. Extract its SHA-256 certificate fingerprint.
3. Add the final statement to `web/public/.well-known/assetlinks.json`.
4. Run Collie's normal root build so the web asset passes through the production build path.
5. Verify `web/dist/.well-known/assetlinks.json` exists and matches the source.
6. Deploy the forked Collie web build to the target origin using the existing install kind's update
   path.
7. Verify the deployed endpoint is a redirect-free 200 JSON response.

**Exit gate:** the live origin names the exact application ID and release certificate that will sign
the APK.

### Phase D — build and install the private release

1. Build the release-signed APK with the pinned toolchain.
2. Verify the APK signature and compare its fingerprint with the deployed statement.
3. Record artifact checksum, `versionCode`, `versionName`, source commit, toolchain versions, and
   signing certificate fingerprint in the local release record.
4. Install with `adb install` for the first build or `adb install -r` only when signature and package
   continuity have been confirmed.

**Exit gate:** Android accepts the signed APK and the installed package reports the expected version
and signer.

### Phase E — device acceptance

Run every test in section 14 on the S25 Ultra. Do not call the release a TWA based solely on the
absence of build errors.

---

## 14. Verification and acceptance criteria

### 14.1 Build-time checks

- [ ] The root Collie build completes, including both TypeScript checks.
- [ ] `web/dist/.well-known/assetlinks.json` exists after the production build.
- [ ] The source and built Digital Asset Links files are identical.
- [ ] The Android release build completes through the committed Gradle wrapper/Bubblewrap workflow.
- [ ] The APK passes `apksigner verify --verbose --print-certs`.
- [ ] APK signer, keystore certificate, and deployed SHA-256 fingerprint match.
- [ ] The merged manifest targets API 36 and uses no cleartext traffic.
- [ ] The merged manifest contains no unexpected dangerous permissions or exported components.
- [ ] The APK and AAB, if produced, are absent from `git status`.
- [ ] The MIT license and original copyright notice remain in the fork and distribution materials.

### 14.2 Endpoint checks

- [ ] `GET /.well-known/assetlinks.json` returns 200.
- [ ] The response has `Content-Type: application/json`.
- [ ] The response has no redirects.
- [ ] The JSON parses and contains the exact package ID and release fingerprint.
- [ ] `/manifest.webmanifest`, `/sw.js`, `/`, and `/api/health` remain reachable through the same
  HTTPS origin.
- [ ] No public internet/Funnel route was created as part of the work.

### 14.3 Installation and TWA verification

- [ ] A fresh `adb install` succeeds.
- [ ] Launcher name and adaptive icon render correctly.
- [ ] Cold launch shows an appropriate splash and reaches `/`.
- [ ] The app remains portrait-oriented as specified by the current PWA.
- [ ] No URL bar, toolbar, or other browser chrome appears at the verified origin.
- [ ] Android/browser logs show successful origin relationship validation.
- [ ] An intentionally invalid fingerprint in a disposable test build produces the expected Custom
  Tab fallback; the valid statement is restored immediately afterward.
- [ ] Internal Collie routes remain inside the trusted fullscreen scope.
- [ ] An off-origin link does not inherit trusted fullscreen treatment.
- [ ] Back, Home, Recent Apps, process death, and relaunch behave as a single stable app task.

### 14.4 Collie behavior

- [ ] Snapshot, session, pane, settings, history, and update routes render.
- [ ] A terminal write succeeds on a paired device.
- [ ] An unpaired or rejected device remains read-only and shows the existing pairing remedy.
- [ ] Existing pairing storage is either retained or the required re-pair is documented.
- [ ] Service-worker update behavior still activates a newly deployed web build.
- [ ] After one successful load, an offline launch renders the cached app shell and fails API-backed
  content honestly rather than showing a blank native screen.
- [ ] Returning online restores ordinary polling without reinstalling the APK.
- [ ] Voice input, if enabled for this installation, follows browser permission behavior and does not
  require an Android-native implementation.

### 14.5 Notification matrix

- [ ] Notification permission grant and denial both produce coherent settings state.
- [ ] `collie push-test` delivers with the app foregrounded, backgrounded, and after client discard.
- [ ] System UI attributes delegated notifications to Collie.
- [ ] Notification icon is legible in light mode, dark mode, status bar, lock screen, and shade.
- [ ] Visible-client suppression works.
- [ ] Tag replacement and retraction work without stale shade entries.
- [ ] Pane deep links preserve pane, host, and session scope.
- [ ] Update notifications open `/settings/updates`.
- [ ] A tap opens/focuses the TWA with no browser chrome.
- [ ] Notification disable/re-enable does not create uncontrolled duplicate subscriptions.

### 14.6 Update and recovery

- [ ] A second APK with a higher `versionCode` and the same signing key installs with `adb install -r`.
- [ ] The update preserves package-level notification permission where Android permits it.
- [ ] A web-only Collie rebuild becomes visible in the TWA without an APK update.
- [ ] Clearing site data produces a predictable re-pair/re-subscribe path.
- [ ] A bad or unavailable origin fails as browser content; it does not expose a privileged native
  fallback.
- [ ] The keystore backup can be located by the operator without copying it into the repository.

Every unchecked acceptance item MUST be recorded with an explicit blocked, failed, or deferred
disposition. The first private APK is accepted only when all non-Play items required for the chosen
device are passing.

---

## 15. Release procedure for the private APK

For each private Android release:

1. Start from a clean, reviewed fork commit.
2. Run the normal Collie build and relevant web tests.
3. Confirm the deployed PWA manifest and Digital Asset Links response.
4. Build with the pinned Android/Bubblewrap toolchain and the external release keystore.
5. Verify signature, package ID, version, target SDK, and artifact checksum.
6. Install on the device and execute the smoke subset: fullscreen trust, one read, one paired write,
   one background push, and one notification deep link.
7. Retain the APK and checksum in an operator-controlled release location, not in Git.
8. Record the source commit and toolchain versions alongside the artifact.

If a release changes only the web application, use Collie's existing deployment/update path; do not
rebuild the Android shell merely to package web bytes it does not contain.

Rollback is channel-specific:

- A bad web deployment is rolled back with the existing Collie release/update procedure.
- A bad APK may be replaced by a higher-`versionCode` APK signed with the same key.
- Android does not ordinarily permit an in-place downgrade. Uninstall/reinstall is destructive to
  package-level state and may require re-pairing or re-subscribing, so it is a last resort.
- A Digital Asset Links mistake is corrected at the web origin, then relationship verification is
  retried; shipping another identical APK is unnecessary if its signer and package were already
  correct.

---

## 16. Later Play distribution phase

Google Play publication requires a new review because the operational model is a private tailnet
application whose backend reviewers cannot ordinarily reach.

Before Play work starts, decide:

- whether distribution is public, closed testing, internal testing, or permanently private to an
  organization;
- how a reviewer can evaluate an application whose configured origin is unavailable outside the
  operator's tailnet;
- whether the application ID and branding are appropriate for a public listing;
- Play App Signing and upload-key custody;
- how the Play app-signing certificate will be added to Digital Asset Links without breaking
  sideloaded builds; and
- store privacy, data-safety, support, screenshots, and policy declarations.

As of 2026-09-04, new apps and app updates submitted to Google Play must target Android 16/API 36.
The Android project targets API 36 from the first private build so later evaluation does not begin
with a mandatory target-SDK migration. Store rules are time-sensitive and MUST be rechecked against
current official Android documentation before submission.

Play publication MUST NOT make the Collie origin public merely to satisfy review. If a safe review
path cannot be designed without weakening the deployment, store publication remains deferred while
private signed distribution continues.

---

## 17. Risks and mitigations

| Risk | Consequence | Mitigation / release gate |
| --- | --- | --- |
| Missing or mismatched Digital Asset Link | Custom Tab chrome instead of verified TWA | Three-way fingerprint comparison and device log verification |
| Wrong browser provider/profile | Pairing and push state appear missing | Use existing pairing flow; document the selected provider; never copy tokens natively |
| Lost signing key | Future APKs cannot update the installed package | External backup and recovery check before first reliance |
| Play signs with a different certificate | Play build loses TWA verification | Publish Play app-signing fingerprint before testing delivered build |
| Generated project drifts | Non-reproducible or permission-expanding builds | Pin tools, commit wrapper/config, inspect merged manifest and dependencies |
| Browser/TWA behavior changes independently | Regression without APK source change | Keep a small device smoke matrix and re-run it on provider updates |
| Private tailnet blocks association fetch | Verification falls back despite correct JSON | Verify direct browser reachability on-device and inspect origin-verifier logs |
| Delegated notification permission denied | No Android-attributed alerts | Expose existing settings state and document OS permission recovery |
| Duplicate browser subscription | Repeated notifications or stale endpoints | Inspect `collie push list`; use existing replacement/forget flows |
| Fork accumulates upstream conflicts | Maintenance burden | Contain changes in `android/` plus one static web file and narrow scripts/docs |
| Public-store pressure weakens backend security | Remote-shell exposure | Keep Play separate; never enable Funnel for review |

---

## 18. Definition of done

The first milestone is done only when:

- the fork contains the reviewed Android project and final Digital Asset Links source;
- no signing secret or generated release artifact is tracked;
- the live origin returns the exact association for the installed APK;
- the release-signed APK installs and launches as a verified, chrome-free TWA on the S25 Ultra;
- pairing, a terminal write, offline shell, Web Push, notification attribution, and deep-link cold
  start have all passed;
- update and key-recovery procedures are documented and exercised to the extent described above;
- the original MIT attribution remains; and
- all failed, blocked, or intentionally deferred checks are listed honestly in the implementation
  handoff.

The existence of an APK, a successful Bubblewrap command, or a page that opens in a Custom Tab does
not satisfy this definition.

---

## 19. Authoritative references

- [Trusted Web Activity overview](https://developer.chrome.com/docs/android/trusted-web-activity)
- [Trusted Web Activity quick start and Bubblewrap flow](https://developer.chrome.com/docs/android/trusted-web-activity/quick-start)
- [Bubblewrap CLI and `twa-manifest.json` reference](https://github.com/GoogleChromeLabs/bubblewrap/blob/main/packages/cli/README.md)
- [Configure website associations with `assetlinks.json`](https://developer.android.com/training/app-links/configure-assetlinks)
- [`TrustedWebActivityService` notification delegation](https://developer.android.com/reference/androidx/browser/trusted/TrustedWebActivityService)
- [Android application signing](https://developer.android.com/studio/publish/app-signing)
- [Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Collie architecture](./ARCHITECTURE.md)
- [Collie security model](./docs/security.md)
- [Collie voice and Web Push guide](./docs/voice-and-push.md)
- [Collie MIT license](./LICENSE)
