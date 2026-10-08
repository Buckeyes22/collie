# Collie for Android

A native Android app for checking on terminal-based AI agents and replying from your phone.
Connect to your own [Collie server](https://github.com/AltanS/collie), inspect the agents that
need input, and use native text fields, prompt buttons and terminal keys to respond.

This is the Android-focused fork maintained at **[Buckeyes22/collie](https://github.com/Buckeyes22/collie)**.
The upstream [AltanS/collie](https://github.com/AltanS/collie) project provides the bridge and mobile
web app. This client uses Kotlin and Android views and connects to the same HTTP API.

## What you can do

The app follows the agents already running on your server.

- Find agents waiting for input from the dashboard.
- Browse spaces, tabs and panes across your configured hosts and sessions.
- Read transcript history or the terminal mirror, and search older turns.
- Answer supported agent prompts, send replies, attach images, and use terminal keys.
- Pair the phone for write access and revoke devices through Settings.
- Use voice input when your server has speech-to-text configured.
- Inspect Pack state and manage **server** updates.

Background push notifications are not implemented in the native app. Server updates do not
update the Android APK. Some agent interactions depend on the bridge's journal and multiplexer
capabilities; unfamiliar terminal screens may require raw controls.

## Requirements

You need a reachable Collie HTTPS server as well as an Android phone.

| Requirement | Details |
| --- | --- |
| Android | Android 8.0 / API 26 or newer |
| Server | A self-hosted Collie deployment; the current development baseline is server 1.5.1 |
| Network | Usually Tailscale on the host and phone, with access to the same tailnet |
| Write access | A short-lived pairing code generated on the host with `collie pair` |

The app starts with an empty server field. It does not provide a hosted server or install agents
on your machine. Start with the upstream [server installation guide](https://github.com/AltanS/collie/blob/main/docs/install.md)
if you do not have a deployment yet. Herdr is the primary server backend; upstream marks tmux
and zellij support experimental.

## Install and connect

Use [Getting started](android/GETTING_STARTED.md) for APK installation, pairing and troubleshooting.

The fork has **no published Android release yet**. The release tooling is prepared, but signing
configuration and device acceptance must be completed before the first APK is published.
When releases are available, they will use `android-v…` tags on this repository's
[Releases page](https://github.com/Buckeyes22/collie/releases).

To build a development APK now:

```bash
git clone https://github.com/Buckeyes22/collie.git
cd collie/android
./gradlew --no-daemon assembleDebug
```

Use JDK 21 and an Android SDK containing platform 36 and Build Tools 36.0.0. Set `ANDROID_HOME`
to your SDK directory. The APK appears under `app/build/outputs/apk/debug/app-debug.apk`;
it installs as `com.lateapex.collie.debug` and can coexist with the release app.

## Security and privacy

Pairing gives the phone control of real terminal sessions with the host user's privileges.

Use a private tailnet or another authenticated ingress. The client requires HTTPS, verifies
certificates, refuses API redirects, and stores its pairing credential encrypted through Android
Keystore. The server's access policy remains essential: device pairing gates writes, while reads
depend on the server's ingress and identity policy.

Diagnostic capture is off by default for new installs. If enabled, it records terminal content
and messages locally; review an export before sharing it. Pairing bodies and Authorization header
values are excluded. See [Privacy](PRIVACY.md) and [Security reporting](SECURITY.md).

## Documentation and development

Start with the guide for your task.

| Guide | Purpose |
| --- | --- |
| [Getting started](android/GETTING_STARTED.md) | Install, connect, pair and troubleshoot |
| [Android developer guide](android/README.md) | Architecture, toolchain, testing and native capabilities |
| [Contributing](CONTRIBUTING.md) | Development setup, checks and PR expectations |
| [Android releases](android/RELEASING.md) | Signing, versioning, acceptance and draft releases |
| [Android changelog](ANDROID_CHANGELOG.md) | Changes for app releases |
| [Privacy](PRIVACY.md) | Network access, local data, diagnostics and deletion |
| [Release preparation status](RELEASE_READINESS.md) | Verified checks and remaining launch requirements |

The upstream bridge, CLI and web source remain in this repository to develop and test the client
against the server contract. Their original introduction is preserved in
[UPSTREAM_README.md](UPSTREAM_README.md); the server guides remain under `docs/`.
Their installer and updater continue to target upstream server releases. Android publishing uses
the separate [Android workflow](.github/workflows/android-release.yml).

## License and attribution

Collie is released under the [MIT license](LICENSE), with the original upstream copyright notice
preserved. See [Acknowledgments](ACKNOWLEDGMENTS.md) for the upstream project and the native app's
bundled asset notices. Product names and marks belong to their respective owners.
