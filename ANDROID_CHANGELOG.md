# Collie for Android changelog

Android app releases use `android-vX.Y.Z` tags. The inherited server/PWA history remains in
[CHANGELOG.md](CHANGELOG.md). The current app is version 1.5.2, versionCode 3,
the first Android release from this fork.

## Unreleased

- Sync the server foundation to upstream Collie 1.19.0 and adapt Crew, scoped configuration, paired reads, device expiry, mounted HTTPS origins, launcher and worktree contracts, upload capabilities, notification fields, and update progress.
- Recover the requested session on narrow snapshots and resolve the local lead identity before pane write checks.
- Read live chat with generation/revision cursors, replace updated turns, show queued messages, and discard abandoned turns and stale generations; older servers retain the history fallback.
- Require an explicit native confirmation for launchers marked as skipping permission prompts.
- Recognize current Codex composers and verify typed replies and submission before clearing the phone draft.
- Read and close HTTP responses off the main thread, handle interrupted bodies without losing drafts, and honor longer upload, worktree and transcription timeouts.
- Isolate cached panes and pending terminal writes by paired connection, reset stale screens on connection changes, and avoid duplicate revocation handling.
- Retain uploaded attachments through failed sends and screen recreation; support image-only replies and bound panoramic thumbnail decoding.
- Match internal links to the complete HTTPS origin and mount, exclude mounted pairing bodies from diagnostics, and prevent pairing-code autofill or saved state.
- Handle forward deletion from Android keyboards and disable unsupported multiplexer chords without disabling supported plain keys.
- Match the server's 48-character device-label limit and use an install-local random label suffix instead of Android ID.
- Use Android's recommended 48 dp touch targets and wrap keyboard rows on narrow phones; update Claude review and fork command help.
- Wait for image uploads and transcription before submitting a reply, preserving pending media through composer sends.
- Explain missing Hermes and OMP session integrations without advising a hook for self-discovered Muse sessions.
- Keep Codex accepted-message previews across terminal updates and rotation until a new matching history turn appears or the preview is explicitly dismissed; report acceptance separately from dispatch.
- Extend 48 dp touch targets to host/session pills, dashboard resume and history pagination.
- Keep the pairing bearer off the public standby update-status request.
- Recheck microphone guards after permission prompts, cancel held keys when the pane stops, and dismiss launcher confirmations for already-cancelled callers.

- Align the Kotlin compiler, serialization plugin and application Kotlin libraries on 2.2.21; update Android Gradle Plugin to 8.10.1 for its compatible release shrinker while retaining Gradle 8.11.1.

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
