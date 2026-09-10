# 0036 — The Android app is a native REST client

Status: **Accepted** (2026-09-04)

Supersedes: [ADR 0035 — The Android app is a TWA, not a WebView](./0035-the-android-app-is-a-twa-not-a-webview.md)

Implementation requirements: [Native Android implementation plan](../ANDROID_NATIVE_IMPLEMENTATION_PLAN.md)

## Context

The TWA selected in ADR 0035 preserved Collie's browser origin, storage, service worker, and Web
Push implementation, but it also kept the Android package dependent on a supporting browser,
Digital Asset Links, browser-profile state, and notification delegation. The requested product is
instead an independent Android application with native screens and lifecycle behavior.

Collie's bridge already exposes the state and actions that the web client consumes as an HTTP API.
A native client can use that API without broadening CORS because CORS is a browser boundary, not a
restriction on an Android HTTP client. The bridge's existing write protections still apply: the
client must present the paired bearer credential and send an `Origin` header matching the HTTPS
request origin for mutations. Rejecting redirects prevents that bearer from crossing origins.

Native Android cannot reuse a browser Push subscription. Pretending otherwise would preserve the
TWA dependency or quietly introduce Firebase and a second notification contract before its server
side identity, lifecycle, and deduplication rules have been designed.

## Decision

**Build Collie for Android as an independent Kotlin client of the existing bridge API.** Use native
Android UI and lifecycle components, an OkHttp repository boundary, typed JSON models, and
lifecycle-aware polling. Accept only normalized HTTPS origins, reject API redirects, send the
configured origin on mutations, and keep the pairing bearer encrypted with a non-exportable
Android Keystore key.

Render pane output as inert text. Do not embed a WebView, Custom Tab, Trusted Web Activity,
Capacitor runtime, browser-helper library, or JavaScript bridge. Do not copy browser storage into
the app. The PWA remains a separately supported client of the same bridge.

Ship the first native slice without background push. Add native notifications only after a
separate provider contract covers authenticated registration, replacement, revocation,
deduplication, credential custody, and exact-pane deep links. A notification must never authorize
a terminal write.

## Consequences

- Android UI, navigation, state, polling, caching, and credential storage now have their own native
  implementation and test surface; web UI releases no longer update the APK automatically.
- The Android package no longer depends on Digital Asset Links, a browser provider, browser-profile
  pairing state, TWA notification delegation, or a reachable public web manifest.
- The native client reuses the bridge's HTTP and pairing contracts without adding Android-specific
  CORS exceptions or weakening TLS, origin, or write authorization checks.
- Browser Web Push continues for the PWA. Native background notifications remain explicitly absent
  until the second delivery path and its server-side security model are implemented.
- Revisit this decision only if a required Android capability cannot be delivered through the
  native API client. Any proposal to embed remote Collie content must define its credential,
  origin, rendering, and native-bridge boundaries and supersede this ADR.
