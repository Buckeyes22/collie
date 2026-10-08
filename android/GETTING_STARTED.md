# Getting started with Collie for Android

Connect the native app to your own Collie server and pair your phone for terminal control.

## Before you start

You need Android 8.0 or newer and a reachable Collie HTTPS server.

The app is a client of the bridge API. Install the server using the upstream
[installation guide](https://github.com/AltanS/collie/blob/main/docs/install.md) if needed.
The current development baseline is Collie server 1.5.1; other versions need compatibility testing.
For the default setup, sign the host and phone into the same Tailscale network.

> **Caution.** Terminal control runs with the host user's privileges. Keep the server behind
> a private tailnet or an authenticated ingress and configure its access policy before pairing.

## Install the app

Download Android APKs from this fork's Releases page once a release is published.

There is no published Android APK yet. The first release needs signing configuration and the
[release acceptance checks](RELEASING.md#device-acceptance). Until then, build a debug APK using
the [developer guide](README.md#validate-and-build).

Published APKs will be named `collie-android-X.Y.Z.apk` under `android-vX.Y.Z` tags at
[Buckeyes22/collie releases](https://github.com/Buckeyes22/collie/releases). Server tarballs from
upstream are separate downloads.

1. Download the APK and its release verification files.
2. Verify the APK checksum against `SHA256SUMS` before installing.
3. Open the APK on your phone and allow installation for the app that opened the file.
4. Launch Collie.

Release APKs use `com.lateapex.collie`. Development APKs use `com.lateapex.collie.debug` and have
their own connection state. Updating requires the same application ID and signing certificate.
If Android reports an incompatible app, check the signer and installed package before uninstalling;
uninstalling deletes local app data and requires pairing again.

## Connect and pair

Get the server's HTTPS URL from the host and enter it in the app's empty server field.

```bash
collie url
```

For a Herdr-managed server, use its plugin action:

```bash
herdr plugin action invoke url --plugin herdr.collie
```

Use the root origin, such as `https://collie.example.com/`, without a path, query, fragment or
embedded credentials. The app rejects plain HTTP and invalid certificates.

1. Enter the server URL in Collie.
2. Choose a device label you can recognize later.
3. Generate a pairing code on the host:

   ```bash
   collie pair
   ```

4. Enter the short-lived code in the app.
5. Tap **Pair and connect**.

On a Herdr-managed server, invoke `collie pair` using the server checkout's compiled binary if
the `collie` name is not on PATH; the server's [commands guide](../docs/commands.md) covers this.
Enter pairing codes manually in the native app; browser pairing links are not registered as
Android App Links.

The dashboard should load after pairing. A harmless reply in a disposable test pane confirms
write access. Reads alone do not prove that writes are authorized.

**Connect read-only** lets you inspect a server whose read policy permits it. Terminal actions
stay disabled until pairing and a fresh server snapshot establish write access.

## Daily use

The dashboard brings agents needing input to the top.

Tap a pane to read the transcript or terminal mirror. Use supported prompt buttons, the reply
field, Quick replies or Keys. Direct terminal typing needs a deliberate mode choice; a changed
or unrecognized prompt can refuse a send.

Voice input requires microphone permission and a server-configured speech-to-text provider.
The app's notification preferences control server preferences; native background push is not
implemented. Foreground polling pauses when the app leaves the foreground.

The **Server updates** screen manages the bridge. Install a newer Android APK separately from
this fork's Releases page, preserving the same signer.

## Troubleshooting

Use these checks before filing an issue.

| Symptom | Check |
| --- | --- |
| Cannot connect | Confirm Tailscale is active, the HTTPS URL is reachable, and the server is running. |
| Invalid origin | Enter only the HTTPS root origin; remove paths, queries and fragments. |
| Certificate failure | Repair the server certificate or hostname; the app has no TLS bypass. |
| Pairing refused | Generate a fresh code and check the server's identity and ingress policy. |
| Read-only after pairing | Refresh and inspect device authorization; a revoked or unverified device cannot write. |
| No history | The server needs a journal adapter and a session log for that pane. |
| No background notification | Native background push is not implemented. |
| Update screen changed the server only | Update the APK separately; server and app versions are independent. |

Include the Android app version, server version, device model, Android version, and reproduction
steps in a [bug report](https://github.com/Buckeyes22/collie/issues/new/choose). Review screenshots
and diagnostic exports for sensitive terminal content before attaching them.
