# 0035 — The Android app is a TWA, not a WebView

Status: **Superseded by [ADR 0036](./0036-the-android-app-is-a-native-rest-client.md)** (2026-09-04)

Governing requirements: [Android TWA implementation specification](../ANDROID_TWA_SPEC.md)

## Context

Collie already ships the application surfaces an Android package needs: an installable PWA, an
offline app shell, same-origin web and API traffic, browser-owned pairing credentials, and VAPID Web
Push with notification deep links. The Android milestone needs a signed APK and app-attributed
notifications, not a second client implementation.

A Capacitor or custom WebView shell is the obvious competing road. Bundling the web client there
would give it a different origin, so Collie's same-origin API and credential model would need CORS
and security exceptions. Its existing browser Push API path would also need replacement or a native
bridge, normally adding Firebase Cloud Messaging and a second subscription format. Such a bridge
would expose remote-terminal content and commands to native code without providing a capability
required by the first Android release.

A Trusted Web Activity instead renders the deployed PWA through a supporting browser. It preserves
the origin, browser storage, service worker, and Web Push implementation while still providing an
Android package. The trade is that fullscreen treatment depends on the package's signing identity
being associated with the web origin through Digital Asset Links.

## Decision

**Build the first Collie Android app as a Trusted Web Activity over the existing HTTPS PWA.** Generate
and maintain the shell with pinned Bubblewrap tooling, verify it with Digital Asset Links, delegate
notifications through the browser-supported TWA service, and retain an ordinary Custom Tab as the
visible fallback when trust cannot be verified.

Do not package Collie in a WebView or Capacitor shell, add a native JavaScript bridge, broaden CORS,
copy pairing credentials into Android-owned storage, or introduce a second push implementation.
The deployed PWA remains the UI and the existing same-origin API remains its backend.

## Consequences

- The Android project stays thin: web releases continue through Collie's existing deployment path
  and usually require no APK rebuild.
- A release certificate and a matching `/.well-known/assetlinks.json` statement are part of the
  app's identity. A missing or mismatched association intentionally exposes browser chrome instead
  of silently falling back to an embedded WebView.
- The selected browser provider owns site data, permissions, service workers, and Push API state.
  A different provider or profile can require pairing and notification setup again; the Android
  shell does not migrate that state.
- Fullscreen behavior and notification delegation depend on a compatible browser provider, so
  provider updates remain part of Android acceptance testing.
- Revisit this decision only when a required native-only capability cannot be delivered safely by
  the PWA/TWA model. That proposal must define its origin, credential, push, and native-bridge
  security boundaries and supersede this ADR rather than quietly adding a WebView path.
