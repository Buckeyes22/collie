# Collie for Android changelog

Android app releases use `android-vX.Y.Z` tags. The inherited server/PWA history remains in
[CHANGELOG.md](CHANGELOG.md). The current app is version 1.6.0, versionCode 4.

## Unreleased

## 1.6.0 - 2026-10-14

- Reconcile the native client with official Collie v1.21.0 and unmodified Herdr v0.9.4. Required workflows use Collie HTTP without private Herdr protocol extensions.
- Add durable-agent Chat, structured asks and answers, guarded send/steer/stop, declared starters and expandable reply context. Screenless conversations do not offer terminal keys.
- Deliver native UnifiedPush notifications through operator-configured self-hosted ntfy, with encrypted signed payloads, enrollment retirement, preference/snooze controls, Android permission/channel settings and exact-pane taps.
- Add native New, declared launchers, recent/favourite folders, worktrees, recipes and Run controls. Preserve uncertain launch intents and require deliberate same-request recovery where the owning server supports it.
- Complete native mirror scroll, Find, Android text selection/Copy, confirmed Files links and external HTTPS links. Keep navigation bound to its original host, session and pane.
- Browse Files and Changes with Unicode source, inert native previews, authenticated raster images, current source handoff and Last commit details. Preserve the owning host's device-read authorization.
- Render supported Chat/History journals, tool details and authenticated images. Cancel retired reads and prevent unadmitted content from appearing after navigation or pairing retirement.
- Add Crew host/session navigation, machine resource charts and alert settings, deputy health and one-shot confirmed takeover with honest uncertainty recovery.
- Import pasted HTTPS links, Android text shares and QR images through a native preview. Continue admits navigation; pairing links only prefill the form until the operator selects Pair.
- Add opted encrypted offline Dashboard, pane and Chat reads with clear provenance, retention and removal controls. Saved content never grants terminal-write authority.
- Add System, Space Grotesk, Aldrich and admitted operator fonts; preserve the monospace reply composer, themes, Zen, pins, configurable keys and large-text/IME insets.
- Retain bounded non-speech attachments, output masking, scoped cache watches and opt-in diagnostics that exclude pairing bodies.
- Add independent Android release discovery, signed metadata, verified APK downloads and Android-confirmed installation with package, signer and increasing-version checks.

The interface remains English only and requires Android 17 / API 37. Speech-to-text and full
Herdr administration are outside this release. Their existing source and unfinished branches remain
preserved; unavailable custom controls stay hidden or explicitly unavailable. Journals and actions
are limited to the current owning backend's declared support. Live host acceptance uses Linux;
Windows host behavior is not certified by the Pixel checks.

## 1.5.2 - 2026-10-08

- Start public builds without a private server address and accept internal deep links only for the configured server.
- Make diagnostic capture opt-in for new installs, preserving explicit preferences on upgrades.
- Exclude pairing request and response bodies from diagnostics while retaining request status and trace metadata.
- Add Android-first onboarding, privacy, contributor guidance and independent signed APK release preparation.
- Bundle the upstream MIT license inside the APK alongside the existing font and artwork notices.
- Prepare and test the provider-independent native notification contract without enabling delivery.
- Include an APK-linked dependency SBOM in signed assets and require a current high/critical vulnerability scan before CI signing.
- Back off repeated Pane and Space read failures, isolate pane caches by server, and prevent automatic HTTP replay of POST writes.
- Improve dark destructive-button contrast and scale pack diagram labels with system text settings.
- Correct unverified artwork attribution claims and record remaining provenance requirements before release.
- Let setup action buttons grow to keep wrapped labels visible at large font and display sizes.
- Goose's notice names its copyright holder, Block, Inc.
- Claude, Codex, pi, OMP and Antigravity panes show locally authored tiles.
- The pane header shortens the server's reported home, not a hardcoded one.
- The APK packages a license and source line for every runtime library.
- Settings opens an Open-source licenses screen with every packaged notice.
- The open-source licenses screen has a visible heading, sits below the status bar, and uses the monospace face.
- Agent tile accents sit in the corner, clear of the glyph.
