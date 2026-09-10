# Android Trusted Web Activity — comprehensive implementation plan

**Status:** Superseded by [ADR 0036](./.adr/0036-the-android-app-is-a-native-rest-client.md)
and the [native Android implementation plan](./ANDROID_NATIVE_IMPLEMENTATION_PLAN.md) (2026-09-04)

This document is retained as the execution record for the abandoned TWA implementation. Its open
release-identity and device gates are cancelled, not current work.

**Plan date:** 2026-09-04

**Governing specification:** [`ANDROID_TWA_SPEC.md`](./ANDROID_TWA_SPEC.md)

**Source baseline:** Collie `v1.5.0`, commit `2eff683d74511398923d4cb5a5ee7ac4f758ff32`

**Delivery target:** A private, release-signed Collie TWA APK installed and accepted on the
operator's Samsung S25 Ultra

This plan turns the governing specification into ordered implementation work. It adds the Android
shell to this fork, publishes the shell's signing association from the existing Collie origin, and
proves the result on the target phone. It does not implement a WebView, native UI, Firebase push, or
Google Play publication.

No phase may claim completion without the exit evidence named for that phase. Commands shown below
are command shapes; paths and identifiers marked as inputs must be resolved before execution.

### Execution snapshot — 2026-09-04

- P1–P4 and the non-secret CI/documentation work in P10 are implemented on `feat/android-twa` in
  `https://github.com/Buckeyes22/collie`.
- The API 36 debug package builds, passes lint and identity checks, and launches through the TWA
  lifecycle on an API 36 emulator. This is compile/lifecycle evidence, not release acceptance.
- P5–P8 remain deliberately incomplete: no release key exists, the live Digital Asset Links URL
  still returns 404, and no release-signed APK has been produced.
- The Samsung S25 Ultra is now attached through wireless `adb`. The debug APK installs and launches,
  the tailnet-only origin loads, the browser profile is paired as `S25-Ultra`, and background Web
  Push delivery plus notification-click navigation pass under the expected unverified fallback.
- P9 release acceptance remains incomplete: the debug signer correctly produces a Custom Tab,
  Chrome-attributed notifications, and unverified app-link state. Release-signed installation,
  chrome-free fullscreen trust, Collie-attributed notification delegation, offline-shell behavior,
  off-origin handling, and update continuity wait for P5–P8.
- The provisional package ID `com.lateapex.collie` is present in source but MUST be confirmed before
  key generation, Digital Asset Links publication, or installation as the durable private release.

---

## 1. Outcome, constraints, and critical path

The milestone produces four durable outcomes:

1. a reviewed, reproducible Bubblewrap/Gradle project under `android/`;
2. a release certificate association at
   `https://ed8.taile7b6b1.ts.net/.well-known/assetlinks.json`;
3. a signed APK whose package ID and signer exactly match that association; and
4. an acceptance record proving fullscreen TWA behavior, pairing, offline shell, delegated Web
   Push, and notification deep links on the S25 Ultra.

The critical path is:

```text
fork remote + final package ID
            │
            ▼
pin Bubblewrap ──► generate/review Android project
            │                    │
            │                    ▼
            └────────────► create release key
                                  │
                                  ▼
                       extract signer fingerprint
                                  │
                                  ▼
                    add + build assetlinks.json
                                  │
                                  ▼
                    deploy and verify live origin
                                  │
                                  ▼
                       build + verify signed APK
                                  │
                                  ▼
                           install on S25 Ultra
                                  │
                                  ▼
                      run full acceptance matrix
```

The release key cannot be created until the application ID is final. The APK cannot be accepted as
a TWA until the live Digital Asset Links response names the APK's actual signer. Device testing may
begin earlier with an ordinary Custom Tab, but that does not advance the fullscreen acceptance gate.

### Non-negotiable constraints

- The PWA and `/api/*` stay on the existing HTTPS origin.
- The Android host never reads or copies Collie's pairing token, browser storage, or terminal data.
- The existing Web Push/VAPID path remains the only push implementation.
- No Firebase project, `google-services.json`, CORS widening, JavaScript bridge, WebView fallback,
  analytics, or Funnel is added.
- Signing material stays outside the repository and out of command output.
- The generated Android project is reviewed and committed; generated build artifacts are not.
- The root Collie version contract remains three files plus `CHANGELOG.md`. Android `versionCode`
  does not become part of `scripts/check-version.sh`.

---

## 2. Work-package map

| ID | Work package | Depends on | Primary output | Exit condition |
| --- | --- | --- | --- | --- |
| P0 | Freeze operator-owned identity inputs | None | Decision record | Fork URL, package ID, key alias/location confirmed |
| P1 | Establish fork and clean baseline | P0 fork URL | Correct remotes and baseline branch | `origin` is writable fork; `upstream` is AltanS |
| P2 | Accept architecture and pin tooling | P0 | Accepted docs, ADR, tool pin | TWA decision durable; Bubblewrap version fixed |
| P3 | Generate and harden Android project | P0, P2 | `android/` source tree | Unsigned/debug build passes; manifest reviewed |
| P4 | Add association validation machinery | P3 | Checker/test hooks | Package/fingerprint drift fails locally and in CI |
| P5 | Create and verify release identity | P0, P3 | External keystore + public fingerprint | Backup verified; no secret is tracked |
| P6 | Publish Digital Asset Links in fork | P4, P5 | `web/public/.well-known/assetlinks.json` | Production web build contains exact statement |
| P7 | Deploy and verify association | P6 | Live 200 JSON endpoint | Redirect-free response matches release signer |
| P8 | Build and verify private APK | P5, P7 | Signed APK + local release record | APK signature/package/version all verified |
| P9 | Accept on emulator and S25 Ultra | P8 | Completed acceptance record | Every required device test passes |
| P10 | CI, documentation, and handoff | P3–P9 | Maintained build path and operator guide | Fresh-checkout validation and release drill pass |

P3 and the non-secret portions of P4 can run in parallel after P2. P5 requires operator interaction
for secret creation and backup. P7 changes the deployed Collie origin and therefore begins only
after the association file has passed local review.

---

## 3. Phase 0 — freeze irreversible inputs

### P0.1 Confirm the writable fork

Record the fork's Git URL without changing remotes yet.

Required result:

```text
FORK_GIT_URL=<writable fork URL>
UPSTREAM_GIT_URL=https://github.com/AltanS/collie.git
```

The checkout currently uses the upstream URL as `origin`. Do not push Android work until Phase 1
renames that remote and connects the fork.

### P0.2 Confirm the application identity

Resolve the provisional values from the specification:

| Input | Proposed value | Confirmation rule |
| --- | --- | --- |
| Application ID | `com.lateapex.collie` | Confirm namespace is acceptable and intended for long-term use |
| App/launcher name | `Collie` | Confirm fork retains Collie branding and MIT attribution |
| Release key alias | `collie-release` | Stable, non-secret, used for every private update |
| Launch origin | `https://ed8.taile7b6b1.ts.net` | Exact scheme and host; no path or trailing-slash ambiguity |
| Initial Android version | `versionCode=1`, `versionName=1.5.0` | Matches first source baseline without extending Collie's version checker |
| Tool pin | `@bubblewrap/cli@1.25.0` | Current stable checked on 2026-09-04; review before execution if delayed |

If the application ID changes, replace it everywhere in this plan during implementation. Do not
allow both the provisional and final ID to survive in tracked files.

### P0.3 Choose release-key custody

Decide and record, without recording secret values:

- the absolute keystore location outside `/home/chris/git/collie`;
- the backup location and recovery owner;
- the password-manager item names for store/key passwords;
- the stable alias; and
- who may produce signed private releases.

The operator must be able to retrieve the backup before the first APK becomes the installed baseline.
Do not ask a build agent to print or persist a Vaultwarden secret. If the vault is used, the operator
provides a `BW_SESSION` only for the active shell and secret values never enter logs or documents.

### Phase 0 exit evidence

- [ ] Writable fork URL recorded.
- [ ] Final application ID recorded.
- [ ] Release-key alias and external storage/backup locations recorded.
- [ ] Launch origin confirmed.
- [ ] No keystore has been created under a provisional application identity.

**Stop condition:** Phase 0 is the only planned point requiring identity choices from the operator.
If any of these values remain unknown, proceed only with reversible research or temporary generation
outside the worktree; do not generate the durable project or signing key.

---

## 4. Phase 1 — establish the fork and baseline

### P1.1 Correct the Git remotes

After the fork exists, preserve upstream and make the fork the push target:

```bash
git remote rename origin upstream
git remote add origin <FORK_GIT_URL>
git remote -v
```

Verify fetch and push URLs independently. A no-op authentication check or dry-run push MAY be used;
do not push a branch until its destination is visibly the fork.

If future upstream merges require history outside the shallow boundary, fetch it deliberately:

```bash
git fetch --unshallow upstream
```

Unshallowing is optional for the Android implementation itself and SHOULD be deferred if the current
network/storage cost is not useful.

### P1.2 Create the implementation branch

Create a branch from the exact verified baseline. Suggested name:

```text
feat/android-twa
```

Before edits, capture:

```bash
git status --short
git rev-parse HEAD
git describe --tags --always
```

The two planning documents are expected untracked files at this point. Preserve them; do not discard
or overwrite unrelated user changes.

### Phase 1 exit evidence

- [ ] `origin` fetch/push URLs name the fork.
- [ ] `upstream` fetch URL names `AltanS/collie`.
- [ ] Implementation branch starts at the documented baseline.
- [ ] Worktree contents and pre-existing changes are recorded.

**Rollback:** Remotes can be renamed back before any push. No deployed or signing state changes in
this phase.

---

## 5. Phase 2 — accept the design and pin the toolchain

### P2.1 Make the decision durable

The specification presents a real, cross-cutting rejected path—WebView/Capacitor—and the reason
spans web origin, push, storage, and security boundaries. It meets this repository's ADR threshold.

Create `.adr/0035-the-android-app-is-a-twa-not-a-webview.md` with:

- **Context:** existing PWA readiness; one-origin API; browser-owned pairing and push; need for an APK;
- **Decision:** the first Android app is a TWA with Custom Tab fallback and no native bridge;
- **Consequences:** DAL/signing dependency, provider-owned storage, browser dependency, and what
  future native requirements would justify revisiting the decision.

Update `.adr/README.md` with entry 0035. Add one short normative line to `CLAUDE.md` linking the ADR.
After review, change the governing spec's status from Proposed to Accepted and record the acceptance
date. Do not duplicate the whole spec inside the ADR.

### P2.2 Pin Bubblewrap without burdening Collie's runtime install

Use `@bubblewrap/cli@1.25.0`, released 2026-07-31 and compatible with Node 18+. Keep the Android-only
tool out of the root dependency tree so `collie build` on small deployment hosts does not install it.

Preferred tracked tooling shape:

```text
android/package.json
android/package-lock.json
```

The package manifest contains only Android generation/build tooling and pins Bubblewrap exactly—no
caret or tilde. Use Node/npm for this isolated tool because Bubblewrap is a Node CLI and the lockfile
must be honored:

```bash
cd android
npm ci
npx --no-install bubblewrap --version
```

If Bubblewrap cannot initialize into a non-empty `android/` tooling directory, generate into a
temporary directory, then move only the reviewed project files into `android/` with `apply_patch` or
an explicit generated-file copy step. Never install Bubblewrap globally or with `sudo`.

### P2.3 Record the Android toolchain

Track or document:

- Bubblewrap 1.25.0;
- Node 22.x for local generation;
- OpenJDK 21;
- compile SDK 36 and target SDK 36;
- Android build-tools 36.0.0;
- generated Gradle wrapper version;
- Android Gradle Plugin version; and
- Android Browser Helper/AndroidX Browser versions produced by Bubblewrap.

Do not hand-select Gradle or library upgrades before the generated project builds. Generate first,
inspect compatibility, then change one version at a time with a build after each change.

### Phase 2 exit evidence

- [ ] ADR 0035 and its index entry exist.
- [ ] `CLAUDE.md` has one concise TWA rule and ADR link.
- [ ] Specification status is Accepted.
- [ ] Bubblewrap version is exact and lockfile-backed under `android/`.
- [ ] `npm ci` and `npx --no-install bubblewrap --version` work from a clean Android tool directory.
- [ ] No Android tooling was added to Collie's root runtime dependencies.

---

## 6. Phase 3 — generate and harden the Android project

### P3.1 Preflight the deployed PWA inputs

Before generation, verify the live manifest and assets:

```bash
curl -fsS https://ed8.taile7b6b1.ts.net/manifest.webmanifest | jq .
curl -fsSI https://ed8.taile7b6b1.ts.net/web-app-manifest-512x512.png
curl -fsSI https://ed8.taile7b6b1.ts.net/badge-96x96.png
```

Record manifest name, start URL, scope, display, orientation, theme/background colors, and icon URLs.
The command must fail on an HTTP error; do not generate from cached or manually reconstructed values
without recording why the live manifest was unavailable.

### P3.2 Generate into a disposable directory first

Run Bubblewrap initialization against the live manifest in a temporary directory outside the
tracked Android tree. Use the final application ID and refuse any prompt that would place a release
keystore in the generated directory.

Command shape:

```bash
cd android
npx --no-install bubblewrap init \
  --manifest="https://ed8.taile7b6b1.ts.net/manifest.webmanifest" \
  --directory="<temporary-generation-directory>"
```

At the prompts/config review, set:

- package/application ID to the Phase 0 value;
- app and launcher name to `Collie`;
- start URL and scope to `/`;
- display mode `standalone`;
- portrait orientation;
- theme and background `#0a0a0a`;
- notification delegation enabled;
- fallback `customtabs`;
- initial `versionName` 1.5.0 and `versionCode` 1; and
- launcher/maskable inputs from the existing 512 px tile.

Do not use `--skipPwaValidation` to hide a real PWA problem. If Bubblewrap's validator objects to a
private tailnet property rather than an app defect, record the exact finding and apply the narrowest
documented exception at build time only after review.

### P3.3 Import the reviewed project

Compare generated files against the Bubblewrap manifest and copy the durable project into `android/`.
Keep:

- `twa-manifest.json`;
- Android `app/` source and resources;
- Gradle settings/build files and properties;
- Gradle wrapper scripts, properties, and JAR;
- Android-only `package.json` and lockfile; and
- any generator metadata required by `bubblewrap update`.

Remove or ignore:

- generated keystore;
- `local.properties`;
- `.gradle/` and all `build/` directories;
- APK/AAB outputs;
- generated store listings or one-off validation artifacts not needed to rebuild; and
- absolute workstation paths.

### P3.4 Add Android-local ignore rules

At minimum, `android/.gitignore` must cover:

```gitignore
node_modules/
.gradle/
local.properties
*.jks
*.keystore
*.apk
*.aab
**/build/
.local/
validation/local/
```

Check each secret/output pattern with a disposable dummy file and `git check-ignore -v`. Remove the
dummies afterward and verify `git status --short` contains only intended source.

### P3.5 Normalize and review configuration

Review `android/twa-manifest.json`, Gradle files, and the merged Android manifest for:

- exact application ID and launch origin;
- target/compile SDK 36;
- cleartext disabled;
- only the intended trusted origin;
- Custom Tab fallback, not WebView fallback;
- notification delegation service and small-icon metadata;
- Android 13+ notification permission behavior;
- launcher activity/exported declarations required by Android;
- no camera, microphone, storage, location, contacts, accessibility, overlay, or package-install
  permissions;
- no Firebase/Google Services plugin or dependencies;
- no analytics/crash-reporting libraries;
- no hardcoded signing password/path; and
- original Collie name and icons rendered without unexpected generator branding.

Commands SHOULD include:

```bash
cd android
./gradlew :app:processDebugMainManifest
./gradlew :app:dependencies
./gradlew :app:assembleDebug
./gradlew lint
```

Use the Gradle wrapper only. Do not depend on a global `gradle` command.

### P3.6 Add operator documentation skeleton

Create `android/README.md` covering:

- architecture and relation to the live PWA;
- pinned prerequisites;
- unsigned/debug CI build;
- external release signing inputs;
- DAL generation and three-way fingerprint verification;
- APK build and `adb` install/update;
- origin-verifier log commands;
- pairing and push recovery;
- web-only versus APK updates; and
- rollback limitations.

Use placeholders for secret paths in tracked examples. Do not document a real password-manager value,
keystore path, device serial, or bearer token.

### Phase 3 exit evidence

- [ ] `android/` contains reviewed source and committed Gradle wrapper.
- [ ] Debug/unsigned build passes using the wrapper.
- [ ] Android lint passes.
- [ ] Merged manifest review is recorded.
- [ ] No unexpected permission, component, dependency, origin, or native bridge exists.
- [ ] Every secret/build-output dummy is ignored.
- [ ] `git status` shows no generated binary or signing material.

**Rollback:** Delete only the newly generated Android tree if it has not been committed. Never delete
or replace an external keystore as part of Android-project cleanup.

---

## 7. Phase 4 — add deterministic association checks

### P4.1 Add a repository checker

Create `scripts/check-android-twa.ts`. It must be pure/read-only and fail with actionable messages.
It should validate:

1. `android/twa-manifest.json` parses;
2. `web/public/.well-known/assetlinks.json` parses once that file exists;
3. no placeholder fingerprint remains;
4. every fingerprint is uppercase, colon-separated SHA-256;
5. the Android package ID equals every DAL `package_name` intended for this app;
6. the configured host/start URL/scope equal the specification;
7. notification delegation is enabled;
8. fallback is `customtabs`;
9. app/compile/target configuration resolves to API 36; and
10. source and built DAL files match when `web/dist` exists.

Do not make the checker read the keystore or secret environment variables. Its inputs are public,
tracked configuration only.

### P4.2 Add tests for the checker

Create `scripts/check-android-twa.test.ts` with temporary fixtures for:

- valid configuration;
- malformed JSON;
- placeholder fingerprint;
- lowercase or malformed fingerprint;
- package mismatch;
- wrong host/scope;
- notifications disabled;
- WebView fallback;
- target SDK below 36; and
- source/build DAL drift.

Keep validation logic importable and pure. The command wrapper may read files and set the exit code;
tests should exercise the pure functions without shelling out to Android tools.

### P4.3 Wire checks without burdening normal Collie builds

Add an Android validation script to root `package.json`, for example:

```json
"check:android": "bun scripts/check-android-twa.ts"
```

Do not add Gradle or Bubblewrap work to `collie build`: that command runs on deployment hosts and
must stay focused on the Collie service/web artifacts. The static association checker is cheap enough
for CI and local review; an Android build belongs in a separate CI job.

### Phase 4 exit evidence

- [ ] Checker fails every negative fixture and passes the real tracked configuration.
- [ ] Root tests cover the checker.
- [ ] Normal `collie build` does not install or compile Android tooling.
- [ ] A package/fingerprint/origin drift cannot pass CI silently.

---

## 8. Phase 5 — create the release identity

This phase contains secret-bearing operator actions. It must not run in CI or a captured terminal
session.

### P5.1 Generate the release keystore

Generate the keystore at the Phase 0 external path, using the stable alias and a validity period that
extends beyond 2033-10-22. Prefer Bubblewrap's supported key-generation flow or `keytool`; whichever
is chosen must be documented in `android/README.md`.

Do not put a password literal in the shell command. Enter it interactively or retrieve it into the
active process environment without printing it.

### P5.2 Back up before reliance

Before building the first installed baseline:

1. copy the keystore to the selected encrypted backup;
2. store recovery metadata and password references;
3. verify the backup file exists and has a checksum matching the primary; and
4. perform a read-only `keytool -list` against the backup.

The checksum may be kept in the private release record. Do not commit it if it reveals a private
storage convention the operator does not want published.

### P5.3 Extract the public certificate fingerprint

Use `keytool -list -v` to obtain the SHA-256 certificate fingerprint. Normalize it to uppercase
colon-separated form and capture only the public fingerprint for tracked configuration.

Never capture the full command environment or keystore password in evidence. A review note should
state that the alias, validity, algorithm, and SHA-256 fingerprint were checked without including
private-key material.

### Phase 5 exit evidence

- [ ] Primary keystore exists outside the repository.
- [ ] Backup exists and was opened successfully.
- [ ] Stable alias and sufficient validity were verified.
- [ ] Public SHA-256 fingerprint is available for DAL configuration.
- [ ] `git status --ignored` confirms no keystore/password file is trackable.

**Rollback:** Before any APK is distributed, an incorrect key may be abandoned and securely deleted
from its external locations. After distribution, key continuity is mandatory; do not rotate casually.

---

## 9. Phase 6 — publish Digital Asset Links from the fork

### P6.1 Add the final source statement

Create `web/public/.well-known/assetlinks.json` only after Phase 5 yields the real fingerprint. Use
the final application ID and release certificate. No placeholder may ever be committed or deployed.

The single-app initial file should contain:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.lateapex.collie",
      "sha256_cert_fingerprints": ["<FINAL_RELEASE_SHA256>"]
    }
  }
]
```

If the final application ID differs, update this example and every Android source before proceeding.

### P6.2 Add the required changelog entry

Because this phase changes `web/public/`, add one crisp `### Added` line under `## [Unreleased]`, for
example:

```text
- Android packages can verify this Collie origin through its Digital Asset Links statement.
```

Do not bump `herdr-plugin.toml`, root `package.json`, or `web/package.json` in the functional commit.

### P6.3 Prove production-build copying and serving

Run the normal root build, not only a raw Vite build:

```bash
bun install --frozen-lockfile
cd web && bun install --frozen-lockfile
cd ..
bun run build
```

Then verify:

```bash
test -f web/dist/.well-known/assetlinks.json
cmp web/public/.well-known/assetlinks.json web/dist/.well-known/assetlinks.json
jq -e . web/dist/.well-known/assetlinks.json
bun run check:android
```

Add an automated assertion if Vite ever omits the dot-directory; do not solve that failure with a
bridge API route. The intended artifact remains a static file in `web/dist` served with JSON content
type and `no-cache` under the bridge's existing static-file rules.

### Phase 6 exit evidence

- [ ] Final, placeholder-free DAL source exists.
- [ ] Root production build copies it byte-for-byte into `web/dist`.
- [ ] Android checker passes package, origin, and fingerprint consistency.
- [ ] Changelog has exactly the required functional entry.
- [ ] Version files remain at 1.5.0 until a real Collie release is cut.

---

## 10. Phase 7 — deploy and verify the association

### P7.1 Identify the installation kind

Before changing the live origin, determine whether it is a Herdr-managed checkout or a standalone
binary install. Use the repository's existing status/version/doctor surfaces. Do not guess a
`systemctl` unit or use a command intended for the other install kind.

### P7.2 Deploy through the existing Collie path

Deploy the fork commit containing the DAL file and rebuilt `web/dist` using the installation kind's
normal update/build workflow. A frontend rebuild is live from disk and does not itself require a
bridge restart; a deployment mechanism may still restart as part of its ordinary release flow.

Do not enable Funnel, change the loopback bind, relax Host/origin gates, or bypass the fork's normal
version/build checks to make the JSON reachable.

### P7.3 Verify from workstation and phone

From the workstation:

```bash
curl -fsS -D /tmp/collie-assetlinks.headers \
  -o /tmp/collie-assetlinks.json \
  https://ed8.taile7b6b1.ts.net/.well-known/assetlinks.json
jq -e . /tmp/collie-assetlinks.json
cmp web/public/.well-known/assetlinks.json /tmp/collie-assetlinks.json
```

Review the captured headers for:

- HTTP 200;
- no `Location` header and zero redirects;
- `Content-Type: application/json` (optional charset allowed);
- valid TLS; and
- no authentication/pairing challenge for the static GET.

Open the same URL in the intended TWA provider on the S25 Ultra while connected to Tailscale. A
desktop-only success does not prove that the private origin is reachable by the provider that will
perform verification.

Delete temporary response captures after evidence is recorded; the host is private even though the
fingerprint is public.

### Phase 7 exit evidence

- [ ] Live endpoint is a redirect-free HTTP 200 JSON response.
- [ ] Deployed bytes match the tracked source.
- [ ] Package ID and fingerprint match the Phase 5 public identity.
- [ ] The S25's intended browser can fetch the endpoint over the tailnet.
- [ ] Collie security/front-door settings are unchanged.

**Rollback:** Deploy the preceding known-good web build if the Collie deployment regresses. Removing
the DAL file intentionally disables trusted fullscreen and is safe before APK distribution, but it
should be treated as an incident after users rely on the package.

---

## 11. Phase 8 — build and verify the signed APK

### P8.1 Build with external signing inputs

Use the pinned Bubblewrap CLI or generated Gradle signing path documented in `android/README.md`.
Bubblewrap supports separate keystore and key passwords through
`BUBBLEWRAP_KEYSTORE_PASSWORD` and `BUBBLEWRAP_KEY_PASSWORD`; provide them only in the active shell.

Command shape:

```bash
cd android
npm ci
BUBBLEWRAP_KEYSTORE_PASSWORD='<provided outside history>' \
BUBBLEWRAP_KEY_PASSWORD='<provided outside history>' \
npx --no-install bubblewrap build \
  --signingKeyPath="<EXTERNAL_KEYSTORE_PATH>" \
  --signingKeyAlias="collie-release"
```

Do not copy the resulting APK/AAB into a tracked path. Confirm `android/.gitignore` excludes the
generator's actual output filenames.

### P8.2 Verify the final artifact, not only the requested inputs

Use API 36 build-tools:

```bash
/home/chris/Android/Sdk/build-tools/36.0.0/apksigner verify \
  --verbose --print-certs <SIGNED_APK>
/home/chris/Android/Sdk/build-tools/36.0.0/aapt dump badging <SIGNED_APK>
sha256sum <SIGNED_APK>
```

Confirm:

- APK verification succeeds;
- signer SHA-256 equals the release keystore and live DAL fingerprint;
- package ID is final;
- `versionCode=1` and `versionName=1.5.0`;
- target SDK is 36;
- launch activity and trusted origin are correct; and
- the artifact contains no unexpected package or resource.

### P8.3 Create the private release record

Create an untracked record under `android/validation/local/` or the operator's external artifact
store containing:

- source commit;
- artifact filename, byte size, and SHA-256;
- package ID, version code/name, min/target SDK;
- certificate SHA-256 fingerprint;
- Bubblewrap, Node, Java, Gradle, AGP, and Android Browser Helper versions;
- build timestamp; and
- which acceptance checklist will be used.

Do not include passwords, private-key data, pairing tokens, device identifiers, or terminal content.

### Phase 8 exit evidence

- [ ] Signed APK exists only in an ignored or external artifact location.
- [ ] `apksigner` verification succeeds.
- [ ] Three-way signer/DAL/keystore fingerprint comparison succeeds.
- [ ] Package, version, target SDK, and launch configuration are correct.
- [ ] Artifact checksum and source/toolchain metadata are recorded.
- [ ] Git worktree contains no release binary or secret.

---

## 12. Phase 9 — emulator and S25 Ultra acceptance

### P9.1 Emulator smoke test

Use the installed Android 36 Google APIs image for fast packaging/lifecycle checks before touching
the phone:

- install the release-signed APK;
- verify launcher/splash/activity lifecycle;
- verify package version and signer;
- confirm that inability to reach the private tailnet origin fails as web content, not a native crash;
- inspect permissions and exported components; and
- exercise uninstall/reinstall only on the emulator.

An emulator without tailnet access cannot prove DAL verification, pairing, API behavior, or push.
Record those cases as not applicable to emulator, not as passes.

### P9.2 Install on the S25 Ultra

Prepare the phone:

1. connect it to the same tailnet and verify the origin in the intended browser;
2. enable USB debugging for the installation session;
3. confirm the device in `adb devices` without recording its serial in tracked evidence; and
4. note the selected TWA provider and version.

Install the first build:

```bash
/home/chris/Android/Sdk/platform-tools/adb install <SIGNED_APK>
```

Use `adb install -r` only for later builds after confirming the same package and signer with a higher
`versionCode`. Do not uninstall an existing accepted package merely to bypass an update failure;
diagnose identity/signature mismatch first.

### P9.3 Prove TWA relationship validation

Before launch, clear only relevant diagnostic logs, then capture origin-verifier messages during a
cold launch. Suggested filters include `OriginVerifier` and `digital_asset_links`.

Acceptance requires both:

- no browser URL bar or Custom Tab toolbar at the configured origin; and
- log or provider evidence that the app/origin relationship validated.

If browser chrome appears:

1. compare the installed APK signer to live DAL again;
2. confirm exact package ID and origin;
3. confirm the phone browser can fetch DAL without redirect;
4. confirm the browser is TWA-capable and current;
5. force/retry relationship verification using supported Android/provider diagnostics; and
6. inspect logs before changing code.

Do not use a browser flag that disables DAL verification as acceptance evidence.

### P9.4 Run functional acceptance

Execute and record every non-Play item in the specification's section 14.

Minimum route and lifecycle matrix:

| Area | Test | Expected result |
| --- | --- | --- |
| Launch | Cold start from launcher | `/` opens without browser chrome |
| Routes | Dashboard, space, pane, history, settings, updates | Existing React routes render inside trusted scope |
| Scope | Open pane with `host` and `session` query | Correct pack member/session remains selected |
| Pairing | Attempt write before pairing if storage is absent | Existing read-only/pairing remedy appears |
| Pairing | Pair and send one harmless terminal input | Write succeeds through unchanged API gate |
| Storage | Relaunch/process death | Pairing persists if provider profile storage persists |
| Offline | Launch after one online load with network unavailable | Cached shell renders; API state fails honestly |
| Recovery | Restore network | Polling/API recover without APK reinstall |
| Navigation | Back/Home/Recent Apps/relaunch | One stable task; no duplicate activity stack |
| Scope escape | Open an off-origin link | Browser chrome/trust boundary appears; origin is not inherited |
| Update | Deploy a harmless web-only marker/build | TWA receives new web build without APK update |

Use a harmless terminal target for the write test. Do not type test content into a password prompt or
agent approval dialog.

### P9.5 Run notification acceptance

Ensure Web Push is configured on the Collie bridge. Run this matrix:

| State | Action | Expected result |
| --- | --- | --- |
| Permission undecided | Enable notifications in Collie Settings | Android permission flow resolves once and Settings reflects result |
| Permission denied | Deny, then inspect Settings | No crash or prompt loop; recovery route documented |
| Foreground | Run `collie push-test` while Collie is visible | Existing visible-client suppression behavior holds |
| Background | Run `collie push-test` | Notification is attributed to the Collie app |
| Client discarded | Stop/discard provider process, then push | Notification arrives and tap cold-starts TWA |
| Pane event | Trigger or send pane-scoped notification | Tap opens correct `/pane/<id>` with host/session scope |
| Update event | Send/update notification | Tap opens `/settings/updates` |
| Replacement | Send two updates for same notification tag | Slot replaces according to existing logic |
| Retraction | Resolve/clear an outstanding state | Stale notification closes |
| Toggle | Disable and re-enable notifications | No uncontrolled duplicate subscription remains |

Inspect notification attribution, channel/settings ownership, small icon, badge, title/body, light and
dark shade, lock screen, and tap behavior. Compare `collie push list` before and after re-enabling;
use the existing `collie push forget` flow only for confirmed stale endpoints.

### P9.6 Prove APK update continuity

Create a second local build with `versionCode=2`, unchanged package ID and release signer. Install it
with `adb install -r`, verify success, then confirm pairing/browser state and Android notification
permission remain coherent. This build is an update drill, not necessarily a distributed release.

Revert the tracked version-code experiment unless version 2 is intentionally selected as the first
distributed artifact. Never distribute two different APKs with the same `versionCode` and ambiguous
contents.

### Phase 9 exit evidence

- [ ] Emulator packaging/lifecycle smoke passes where applicable.
- [ ] S25 install succeeds.
- [ ] Fullscreen relationship validation is proven without bypass flags.
- [ ] Pairing and one harmless write pass.
- [ ] Offline shell and online recovery pass.
- [ ] Delegated push attribution and every deep-link state pass.
- [ ] Icon/splash/notification visuals pass on the physical device.
- [ ] Same-signer higher-version-code update drill passes.
- [ ] Every spec checklist item has Pass, Fail, Blocked, or Deferred plus evidence.

**Rollback:** A web regression uses Collie's existing web rollback. A bad APK is replaced by a
higher-version-code APK signed with the same key. Uninstall/reinstall is last resort because it may
discard package notification state and trigger browser re-pair/re-subscribe behavior.

---

## 13. Phase 10 — CI, documentation, and maintainable handoff

### P10.1 Add a separate Android CI job

Extend `.github/workflows/ci.yml` with an Android job that:

1. checks out the repository;
2. sets up JDK 21;
3. installs Node/npm Android tooling with `npm ci` under `android/`;
4. runs the public configuration/DAL checker;
5. runs Android lint;
6. builds an unsigned or debug APK with the committed Gradle wrapper; and
7. uploads no release-signed artifact and uses no signing secret.

Pin action major versions consistently with the existing workflow. Use Gradle caching only if the
cache key is derived from tracked Gradle files and lockfiles. CI must never publish its debug
certificate in the production DAL file.

Keep the current Collie `check` job unchanged unless the cheap `check:android` command is added to it.
Android compilation belongs in its own job so ordinary TypeScript failures remain fast and Android
SDK provisioning is visible.

### P10.2 Finish the Android operator guide

Replace documentation skeletons with verified commands and actual generated filenames. Include:

- fresh setup and tool installation;
- build without signing;
- private signed build with secrets supplied out of band;
- fingerprint extraction/comparison;
- DAL deployment verification;
- `adb` install/update commands using the known SDK path or a documented `PATH` export;
- provider log filters and common DAL failure diagnoses;
- pairing and push recovery;
- web-only release versus APK release distinction;
- release metadata/checksum procedure;
- key loss/compromise response; and
- explicit Play-publication deferral.

Add a tracked `android/ACCEPTANCE_TEMPLATE.md` if the full checklist is unwieldy in the README. Keep
completed device evidence in the ignored local validation directory unless it is sanitized and
valuable to every contributor.

### P10.3 Update discoverability

Add a concise link to the Android guide from the README documentation table or development section.
Do not turn the main Collie quickstart into an Android build guide.

Ensure `ANDROID_TWA_SPEC.md`, this plan, the ADR, and `android/README.md` link to one another by role:

- spec: normative product and acceptance requirements;
- plan: implementation ordering and evidence gates;
- ADR: why TWA and not WebView;
- Android README: commands an operator actually runs.

### P10.4 Run the complete repository gates

Before handoff:

```bash
bash scripts/check-version.sh
bun run lint
bun run typecheck
cd web && bun run typecheck && bun run test
cd ..
bun run test
bun run check:android
cd android && npm ci && ./gradlew lint :app:assembleDebug
```

Also run `bun scripts/check-doc-links.ts`. Existing unrelated failures must be reported separately;
new Android/spec/plan links must all resolve.

### P10.5 Conduct a fresh-checkout drill

In a disposable clean clone of the fork, without copying local Gradle state or signing material:

1. install root/web dependencies using existing frozen-lockfile commands;
2. run the Collie checks;
3. run `npm ci` in `android/`;
4. build the unsigned/debug Android artifact with the wrapper;
5. run the Android checker; and
6. confirm the signed-build instructions stop clearly when external signing inputs are absent.

This proves the repository contains everything public needed to maintain the shell and nothing
private needed to impersonate it.

### Phase 10 exit evidence

- [ ] Android CI job passes without signing secrets.
- [ ] Existing Collie CI remains green.
- [ ] Operator guide commands match actual generated outputs.
- [ ] README links to Android documentation without displacing the normal PWA quickstart.
- [ ] Fresh-checkout unsigned build succeeds.
- [ ] Fresh-checkout signed build cannot proceed without external secrets.
- [ ] Complete test/lint/typecheck/doc-link suite results are recorded.

---

## 14. File-by-file implementation inventory

| Path | Action | Purpose | Validation |
| --- | --- | --- | --- |
| `ANDROID_TWA_SPEC.md` | Update status after acceptance | Governing requirements | Review against ADR and final IDs |
| `ANDROID_TWA_IMPLEMENTATION_PLAN.md` | Track dispositions during work | Execution order and gates | Every package has exit evidence |
| `.adr/0035-the-android-app-is-a-twa-not-a-webview.md` | Add | Durable architectural decision | ADR format and index |
| `.adr/README.md` | Update | Discover ADR 0035 | Doc link checker |
| `CLAUDE.md` | Update narrowly | Prevent WebView/native-bridge regression | ADR link resolves |
| `android/package.json` | Add | Pin Android-only Bubblewrap CLI | Exact version; no runtime deps |
| `android/package-lock.json` | Add | Reproducible Node tooling | `npm ci` |
| `android/twa-manifest.json` | Add | Bubblewrap source of truth | Android checker |
| `android/.gitignore` | Add | Exclude secrets and outputs | Dummy `git check-ignore` audit |
| `android/gradlew*`, `android/gradle/wrapper/*` | Add | Reproducible Gradle entrypoint | Wrapper build from clean clone |
| `android/build.gradle`, `settings.gradle`, properties | Add | Android build configuration | Gradle lint/build and dependency review |
| `android/app/**` | Add | Launcher, resources, TWA/delegation manifest | Merged-manifest and device review |
| `android/README.md` | Add | Verified operator runbook | Command walkthrough |
| `android/ACCEPTANCE_TEMPLATE.md` | Optional add | Repeatable physical-device evidence | Checklist completeness |
| `web/public/.well-known/assetlinks.json` | Add after key creation | Bind origin to package signer | Build copy, JSON, live fetch |
| `scripts/check-android-twa.ts` | Add | Fail package/origin/fingerprint drift | Unit tests and real config |
| `scripts/check-android-twa.test.ts` | Add | Pin negative/positive validation behavior | `bun test ./scripts` |
| `package.json` | Update narrowly | Expose cheap Android configuration check | Version unchanged; checker runs |
| `.github/workflows/ci.yml` | Update | Separate unsigned Android CI | Fork CI pass |
| `README.md` | Update narrowly | Link Android guide | Doc link checker |
| `CHANGELOG.md` | Add Unreleased line(s) | Record functional Android/DAL capability | Version guard passes |

Generated tool output may use `build.gradle.kts`/`settings.gradle.kts` instead of Groovy files. Keep
the generator's internally consistent format; do not rewrite solely to match this inventory.

---

## 15. Commit strategy

Use small commits whose checks and changelog consequences are legible. A suggested sequence:

1. `docs(android): specify the TWA implementation`
   - specification, implementation plan, ADR/index, and concise `CLAUDE.md` rule;
   - doc-only except for `CLAUDE.md`; no version bump; verify whether the repo's hook classifies the
     instruction-file edit as documentation.
2. `feat(android): add the generated TWA project`
   - `android/` source, wrapper, isolated tool lockfile, README skeleton;
   - add one `Unreleased` entry if treated as functional capability.
3. `test(android): enforce TWA identity and association consistency`
   - checker, tests, root script;
   - add/update the crisp `Unreleased` line according to the hook's one-line-per-functional-change
     requirement.
4. `feat(web): publish the Android asset association`
   - final `assetlinks.json` only after release fingerprint exists;
   - required `Unreleased` entry; no version bump.
5. `ci(android): build the unsigned shell`
   - separate Android job.
6. `docs(android): record verified build and device procedures`
   - final runbook and README link.

Before each commit, inspect staged files and run the relevant subset. Never use a skip hatch to hide
a real version, lint, type, or pack-wire failure. A fork PR sent upstream is a different workflow and
would omit changelog/version changes, but this plan targets the maintained fork itself.

Do not cut a new Collie release merely because the functional commits exist. If the fork chooses to
release them, follow `CLAUDE.md` exactly: select the SemVer axis from all Unreleased entries, bump the
three canonical version files together, convert the changelog section, run the version check, and
publish the matching annotated tag. The Android `versionCode` is managed separately.

---

## 16. Failure handling and decision tree

### Bubblewrap cannot consume the private manifest

1. Prove the URL is reachable from the workstation.
2. Run Bubblewrap with verbose diagnostics.
3. Distinguish PWA-quality failure from private-network reachability.
4. If necessary, generate from a byte-identical locally served production manifest in a disposable
   environment, then set the reviewed production host in `twa-manifest.json`.
5. Never solve it by publishing the Collie backend or enabling Funnel.

### Android build fails after generation

1. Capture Java, Gradle, AGP, SDK, and dependency versions.
2. Reproduce with the committed wrapper.
3. Make one compatibility change at a time.
4. Re-run merged-manifest and dependency review after upgrades.
5. Do not skip Android lint or lower target SDK below 36 to get a green build.

### DAL endpoint is correct but browser chrome remains

1. Verify the installed APK signer, not only the keystore.
2. Verify package ID and exact origin.
3. Fetch the endpoint on the phone with no redirect/auth challenge.
4. Inspect provider origin-verifier logs.
5. Check provider capability/version and cached association state.
6. Retry verification through supported diagnostics.
7. Keep Custom Tab fallback visible until trust verifies; never bypass verification in the release.

### Pairing or push state is missing

Treat it as browser-profile state, not an Android token-migration bug. Re-pair through `collie pair`
and re-enable notifications. Record whether the TWA provider/profile differs from the previously
used browser/PWA profile. Do not add a native credential store.

### Notification arrives as Chrome, not Collie

1. Reconfirm DAL verification.
2. Confirm notification delegation is enabled in `twa-manifest.json` and merged manifest.
3. Confirm delegation service, intent filter, token store, and small-icon metadata.
4. Confirm Android notification permission belongs to the Collie package.
5. Check provider support before changing the web push payload.

### Signed update will not install

Inspect `adb`'s exact failure. Compare package ID, certificate fingerprint, and `versionCode` to the
installed package. Do not uninstall until evidence is captured; uninstalling can erase the state
needed to diagnose continuity and may require re-pairing/re-subscribing.

---

## 17. Completion report format

The final implementation handoff must contain:

```markdown
## Android TWA implementation result

- Source commit:
- Application ID:
- Android versionCode/versionName:
- Target SDK:
- Bubblewrap / Gradle / AGP / Browser Helper versions:
- Release certificate SHA-256:
- APK filename and SHA-256:
- Live assetlinks status and verification time:
- S25 Ultra OS / TWA provider version:

### Phase dispositions

| Phase | Status | Evidence |
| --- | --- | --- |
| P0–P10 | Pass / Fail / Blocked / Deferred | command, file, or sanitized observation |

### Required behavior

- Fullscreen DAL verification:
- Pairing and write:
- Offline shell and recovery:
- Delegated push attribution:
- Pane/update notification deep links:
- APK update continuity:

### Limitations and follow-up

- Failed or deferred checks:
- Browser/provider-specific behavior:
- Play publication remains deferred:
- Key custody/recovery verified by:
```

Never put keystore paths, passwords, device serials, bearer tokens, or terminal content in a public
handoff. The release certificate fingerprint and APK checksum are public verification material and
may be reported.

---

## 18. Definition of plan completion

This implementation plan is complete when all of the following are true:

- P0 through P10 have recorded dispositions;
- the fork, not upstream, contains the intended commits;
- the live origin serves the final Digital Asset Links statement;
- the signed APK's actual signer matches the keystore and live statement;
- the S25 Ultra launches Collie as a verified chrome-free TWA;
- pairing, a harmless terminal write, offline shell, delegated Web Push, notification attribution,
  cold-start deep links, and same-signer update continuity all pass;
- no secret, APK, AAB, absolute local path, or private device evidence is tracked;
- the standard Collie checks and separate Android CI build pass;
- operating, recovery, and release instructions are verified from a clean checkout; and
- Google Play remains a separately approved later phase.

An unchecked item is not silently omitted. It receives Pass, Fail, Blocked, or Deferred with a
reason and the next concrete action.
