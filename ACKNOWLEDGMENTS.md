# Acknowledgments

Collie for Android builds on [Altan Sarisin's Collie](https://github.com/AltanS/collie), which
provides the bridge API, PWA, terminal adapter contracts, design language and original artwork.
The Android-focused fork is maintained at [Buckeyes22/collie](https://github.com/Buckeyes22/collie).
The original MIT copyright and permission notice remain in [LICENSE](LICENSE).

## Web components

The retained PWA adapts components from two MIT projects. Their notices ship in the built app
under `/licenses/`:

| Upstream | Files | Notice |
| --- | --- | --- |
| [shadcn/ui](https://github.com/shadcn-ui/ui) | `ui/button`, `ui/badge`, `ui/card`, `ui/sheet`, `ui/switch` | [shadcn-ui.txt](web/public/licenses/shadcn-ui.txt) |
| [shadcn-chat](https://github.com/jakobhoeg/shadcn-chat) | `ui/chat/chat-input`, `ui/chat/chat-message-list`, `hooks/use-auto-scroll` | [shadcn-chat.txt](web/public/licenses/shadcn-chat.txt) |

The native client bundles Aldrich and JetBrains Mono with their font licenses. Agent icons have
their own notices and reuse terms. The APK includes these notices as Android resources:

- [Third-party artwork notices](android/app/src/main/res/raw/third_party_notices.txt)
- [Collie MIT license in the APK](android/app/src/main/res/raw/license_collie.txt)
- [Aldrich license](android/app/src/main/res/raw/license_aldrich.txt)
- [JetBrains Mono license](android/app/src/main/res/raw/license_jetbrains_mono.txt)

The [Android developer guide](android/README.md#bundled-artwork-and-typeface) records the assets'
sources and modifications. Brand marks identify agents supported by the server and do not imply
endorsement. Preserve the notices when redistributing the app.

## Agent tiles

The PWA's agent tiles (`web/src/components/agent-icon-data.ts`) are upstream Collie's selection,
with their recorded sources in that file's header. They identify the agent a pane runs and imply
no endorsement. The Android app does not package the Claude, Codex, pi, OMP or Antigravity marks.
