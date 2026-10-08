# Contributing to Collie for Android

This fork develops the native Android client at [Buckeyes22/collie](https://github.com/Buckeyes22/collie).
Open pull requests against `main`. For server or PWA changes intended for upstream, use
[AltanS/collie](https://github.com/AltanS/collie) and its contribution rules.

## Development setup

Use JDK 21 and an Android SDK with platform 36 and Build Tools 36.0.0.

```bash
git clone https://github.com/Buckeyes22/collie.git
cd collie/android
./gradlew --no-daemon assembleDebug
```

Set `ANDROID_HOME` to the SDK directory before building. Use the committed Gradle wrapper;
there is no need for a global Gradle installation. Keep SDK paths, signing keys, credentials
and local build outputs untracked.

The Kotlin sources are under `android/app/src/main/java/com/lateapex/collie/`; unit tests are
under `android/app/src/test/` and device tests under `android/app/src/androidTest/`.
Read [the Android developer guide](android/README.md) and
[ADR 0036](.adr/0036-the-android-app-is-a-native-rest-client.md) before changing the client boundary.

## Checks before a PR

Run the native checks from the repository root.

```bash
bun run check:android
(cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleRelease)
```

Bun is needed for the repository's static checker, but not for a standalone Gradle build.
Include a focused regression test for credential handling, terminal writes, state recovery or
other behavior that could regress. For UI changes, record the device/API and the relevant
acceptance checks. Review screenshots for private content before attaching them.

If a change touches TypeScript, the bridge, CLI or web client, install both dependency trees
with their frozen lockfiles and run each check from the repository root:

```bash
bun install --frozen-lockfile
(cd web && bun install --frozen-lockfile)
bun run typecheck
(cd web && bun run typecheck)
bun run lint
bun run test
(cd web && bun run test)
```

The parentheses keep the working directory at the root after web commands. The root typecheck
does not cover web test files. Full-tree oxlint remains the lint gate; do not add suppressions
or another linter. `bash scripts/install-hooks.sh` enables the repository's local commit and
push hooks. Read [the working agreement](CLAUDE.md) for their scope and escape hatches.

## Changes and versions

Add Android user-facing changes to [ANDROID_CHANGELOG.md](ANDROID_CHANGELOG.md) under Unreleased.

Keep entries short. Ordinary commits do not bump versions. The Android release commit updates
`versionName` and the monotonic `versionCode`, plus the native checker pins and fixtures.
Android app releases use `android-vX.Y.Z` tags; the server/plugin versions remain independent.
See [Releasing](android/RELEASING.md).

For maintainer changes under the inherited server/CLI/PWA release rules, append a line to the
server [CHANGELOG.md](CHANGELOG.md) without bumping versions. External contributor PRs leave
the server version files and server changelog alone; maintainers record those changes on merge.
Documentation-only changes need no changelog entry.

## Native client rules

Preserve the server's existing security and interaction contracts.

- Keep native Android views and the shared HTTP API boundary.
- Require HTTPS, normal certificate validation and refusal of API redirects.
- Keep pairing credentials encrypted and out of logs, exports and saved state.
- Keep diagnostic capture opt-in and exclude pairing bodies.
- Bind terminal writes to the current visible prompt and authorized device.
- Keep visible strings in the English resource catalog.
- Check established decisions in [.adr/](.adr/) before changing an architectural boundary.

`web/src/components/ui/` holds the upstream web UI components. Read [DESIGN.md](DESIGN.md)
before changing them. [HARNESS_CONTRIBUTING.md](HARNESS_CONTRIBUTING.md) and
[MUX_CONTRIBUTING.md](MUX_CONTRIBUTING.md) cover upstream adapter seams.

## Issues and review

Use this fork's issue templates for Android bugs and feature requests.

Include Android app and server versions separately. Give reproduction steps and expected
behavior; keep pairing codes, bearer credentials and private terminal content out of reports.
Use [private security reporting](SECURITY.md) for vulnerabilities. Contributions receive review
when maintainer time permits; there is no promised response schedule.

> **Note.** Automatic labelling is off. If the maintainer turns it on, a new issue's or pull
> request's title, body, existing label names and, for a pull request, changed file paths are
> sent to OpenRouter for classification. Keep private content out of issues either way.
