# Collie for Android changelog

Android app releases use `android-vX.Y.Z` tags. The inherited server/PWA history remains in
[CHANGELOG.md](CHANGELOG.md). The current development app is version 1.5.1, versionCode 2;
that version has not been published as an Android release by this fork.

## Unreleased

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
