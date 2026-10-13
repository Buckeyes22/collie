# Android production resources audit

Scope: every file under `/home/chris/git/collie/android/app/src/main/res` (167 files). The parent owns XML security/privacy resources, so those two paths are explicitly excluded; the remaining 165 paths have an individual disposition ledger below. Review was static, with resource semantics traced into production code and existing tests. No emulator screenshots or raster visual inspection were performed, so no visual-quality claim is made.

## Requirements and pinned references

- Repository rule: `/home/chris/git/collie/CLAUDE.md` delegates Android implementation rules to the native app; `/home/chris/git/collie/android/README.md:129` documents 48 dp touch targets for native controls. The checked-in UI hardening note `/home/chris/git/collie/android/validation/hardening/ui-review.md:63-71` records the 48 dp minimum and the tested large-font/narrow-screen expectations.
- Android accessibility snapshot: `/home/chris/git/collie/.project-docs/INDEX.md` collection date 2026-10-10; `/home/chris/git/collie/.project-docs/android-official/INDEX.md` → `developer.android.com/guide/topics/ui/accessibility/apps-b66fd978.md`, section “Use large, simple controls” (lines 87–93), recommends a 48 × 48 dp focusable area for touch UI. The same snapshot includes drawable resources, string resources, localization, styles/themes, dark theme, and accessibility pages (see the corresponding index entries).
- Material Android: `/home/chris/git/collie/.project-docs/material-android/INDEX.md`, source revision `88acfcdb63281714d64e863d2e7da3b53b8da9ce`; reviewed relevant `docs/theming/Color.md`, `Dark.md`, `Typography.md`, `docs/components/Button.md`, `BottomSheet.md`, and `docs/adaptive/CanonicalLayouts.md`. App pins Material Components 1.12.0 in `/home/chris/git/collie/android/app/build.gradle.kts:80`.
- Fonts: `/home/chris/git/collie/.project-docs/font-aldrich/INDEX.md`, Google Fonts revision `bd8f81ddb5c74d5c8897b36ad88b440266245103`; `/home/chris/git/collie/android/app/src/main/res/raw/license_aldrich.txt` carries the OFL copyright/license and Reserved Font Name; `/home/chris/git/collie/android/app/src/main/res/raw/license_jetbrains_mono.txt` carries JetBrains Mono’s OFL. `collie_ui.xml` declares only normal 400 and warns against synthetic bold; `collie_mono.xml` declares JetBrains Mono normal 400.
- Third-party artwork provenance/licensing is recorded in `/home/chris/git/collie/android/app/src/main/res/raw/third_party_notices.txt`: Simple Icons 16.21.0 / commit `462b3c9477235a5759d9a8aac6b117628e26c7d4` (Copilot artwork); OpenCode commit `5cf9f517cfec3ef68d3e68a12a6a4b3163947f44`; Lobe Icons commit `4aaf4ee1fb2678a7f989ea570f0f6ce14a9abf75`; Font Awesome Free 6.7.2 / commit `af620534bfc3c2d4cbefcfeec29603bbe7809e64`; Block Goose commit `dce69009546ce5f20522d010fa3f1d57abbe2c3f`. The vectors identify the source in comments; locally authored agent tiles explicitly disclaim vendor artwork. `/home/chris/git/collie/android/app/src/main/res/raw/keep.xml:2` preserves license and notice resources through shrinking.
- Raster inventory: 16 PNG assets are Git-tracked in the app resource tree. `file` confirms PNG encoding and density dimensions; `git log` records them in commit `be300d70ef9640ba7631e56c146cd0b81fc940e5` (TWA shell) and the Collie mark in `4e725c0843b4e349a5a6837b4a938d43f7777db3` (native Kotlin client). These are treated as Collie app artwork; no third-party attribution is asserted from file metadata alone. Exact visual appearance remains unverified in this audit.

## Finding

**F1 — four tappable controls are 44 dp high (P2).** `/home/chris/git/collie/android/app/src/main/res/values/dashboard_scope.xml:5-11` sets `Widget.Collie.Header.Pill` to 44 dp height and minHeight; `/home/chris/git/collie/android/app/src/main/res/layout/activity_main.xml:268-279` applies it to tappable host/session switchers. The same layout sets the tappable `dashboard_idle_resume` MaterialButton height to 44 dp at lines 396–402. `/home/chris/git/collie/android/app/src/main/res/layout/activity_history.xml:199-209` sets tappable `history_load_older` to 44 dp. These miss Android’s documented 48 × 48 dp recommendation (archive link above); related project tests name the 48 dp floor explicitly, but currently omit these controls.

Existing tests miss these controls: `DashboardShellActivityTest.connectionBannerActionsKeepTheFortyEightDpTouchFloor` at lines 60–77 checks only the connection banner retry/reload controls; `HistoryActivityTest` at lines 110–125 checks find and user-jump controls but omits load-older. `SettingsActivityTest` checks its separate control set. Proposed correction: use `@dimen/collie_touch_target` for the style/layout heights and extend those existing tests to assert both switchers, idle resume, and history load-older have at least the token size. Preserve the current visual insets/background if the 48 dp box should not alter painted size. Parent owns applying/validating fixes.

## Verification and limits

Existing coverage relevant to resources includes `AccessibilityContrastTest.semanticTextAndButtonPairsMeetContrastInLightAndDarkThemes`, `AndroidLocalizationTest`, `VisualFoundationsTest`, `AgentIconsTest`, `AgentIconsDeviceTest`, `DashboardShellActivityTest`, `HistoryActivityTest`, `SettingsActivityTest`, `PaneLayoutTest`, `PackFormationLayoutTest`, `PackFormationTextLayoutTest`, and `LicensesActivityTest`. `AgentIconsTest` inflates and draws every mapped agent vector at lines 67–84, checks selected source geometry/authorship at lines 50–124, while `AgentIconsDeviceTest` exercises the OpenCode vector on-device. Contrast coverage tests selected semantic text/status/button pairs in light and dark themes; it does not exhaustively test every color-token pair. Localization coverage checks base English resource parsing, plural `other`, and hardcoded visible copy in layouts/Kotlin; it is not translation coverage.

No tests were run from this subtask because the parent reported an active Gradle final-gate session and owns the final rerun. Missing verification: the existing suite does not assert 48 dp size for the F1 controls, does not render every non-agent drawable on-device, and does not include raster visual QA here. Security/privacy XML was not reviewed by this unit.

## Exhaustive path ledger

Each path under `res` appears exactly once. “No defect found” means source/resource inspection within this bounded unit, subject to the explicit limits above.

| Resource path | Disposition |
|---|---|
| `/android/app/src/main/res/drawable/bg_agent_badge.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_attention_blocked.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_card.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_control_off.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_control_off_default.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_control_off_focused.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_control_on.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_control_on_default.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_control_on_focused.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_focus.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_icon_control.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_input.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_input_default.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_input_focused.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_sheet.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_collie_sheet_grip.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_composer_dock.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_count_chip.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_dashboard_group.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_dashboard_shell_blocked.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_dashboard_shell_done.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_dashboard_shell_working.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_header_pill.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_history_code.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_history_focus.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_history_jump_group.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_history_note.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_history_quote.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_history_table_cell.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_history_tool.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_history_transparent.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_history_user.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_attention.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_card.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_composer_chrome.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_header.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_notice.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_reply.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_reply_default.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_reply_focused.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_send.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_status_band.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_status_dot.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_tab_strip.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_pane_user_turn.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_settings_navigation_press.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_switcher_grip.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/bg_update_section.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_antigravity.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_claude.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_codex.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_copilot.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_generic.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_goose.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_grok.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_hermes.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_kimi.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_omp.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_opencode.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_pi.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_agent_qwen.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_collie_add.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_collie_back.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_collie_close.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_collie_ellipsis.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_collie_layers.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_collie_server.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_collie_settings.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_collie_status_filled.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_collie_status_hollow.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_composer_agent.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_composer_keyboard.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_composer_quick.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_composer_settings.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_composer_stop.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_composer_terminal.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_dashboard_connected.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_dashboard_plug.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_dashboard_reload.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_dashboard_update.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_dashboard_warning.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_history_chevron_down.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_history_chevron_right.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_history_chevron_up.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_history_info.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_history_load_older.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_history_search.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_history_transcript.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_history_user.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_history_warning.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_history_wrench.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_pack_network.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_pane_attach.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_pane_mic.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_pane_more.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_pane_send.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable/ic_settings.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/drawable-hdpi/ic_notification_icon.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/drawable-mdpi/ic_notification_icon.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/drawable-nodpi/collie_mark.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/drawable-xhdpi/ic_notification_icon.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/drawable-xxhdpi/ic_notification_icon.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/drawable-xxxhdpi/ic_notification_icon.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/font/aldrich_regular.ttf` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/font/collie_mono.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/font/collie_ui.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/font/jetbrains_mono_regular.ttf` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/layout/activity_history.xml` | Reviewed; contains F1 under-sized interactive controls at cited lines. |
| `/android/app/src/main/res/layout/activity_licenses.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/activity_main.xml` | Reviewed; contains F1 under-sized interactive controls at cited lines. |
| `/android/app/src/main/res/layout/activity_pane.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/activity_settings.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/activity_space.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/item_dashboard_all_clear.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/item_dashboard_empty.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/item_dashboard_footer.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/item_dashboard_launchers.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/item_dashboard_section.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/item_dashboard_spaces.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/item_pane.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/item_space.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/item_space_tab.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/layout/view_collie_bottom_sheet.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/mipmap-hdpi/ic_launcher.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/mipmap-hdpi/ic_maskable.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/mipmap-mdpi/ic_launcher.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/mipmap-mdpi/ic_maskable.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/mipmap-xhdpi/ic_launcher.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/mipmap-xhdpi/ic_maskable.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/mipmap-xxhdpi/ic_maskable.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/mipmap-xxxhdpi/ic_maskable.png` | Binary asset metadata/provenance/license reviewed; visual appearance not claimed. |
| `/android/app/src/main/res/raw/dependency_notices.txt` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/raw/keep.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/raw/license_aldrich.txt` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/raw/license_collie.txt` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/raw/license_jetbrains_mono.txt` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/raw/third_party_notices.txt` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/colors.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/dashboard_scope.xml` | Reviewed; contains F1 header-pill target-size deviation at lines 5–11. |
| `/android/app/src/main/res/values/dashboard_shell.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/dashboard_shell_colors.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/dashboard_space_parity.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/dimens.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/pack_native.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/pane_key_ids.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/pane_strings.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/pane_styles.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/pane_terminal_parity.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/semantic_native.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/settings_ids.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/settings_native_parity.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/settings_strings.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/strings.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/themes.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/update_native_parity.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values/visual_ids.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values-night/colors.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values-night/dashboard_shell_colors.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values-night/themes.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values-night-v27/themes.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/values-v27/themes.xml` | Source resource reviewed; no defect found except F1 where separately cited. |
| `/android/app/src/main/res/xml/data_extraction_rules.xml` | Excluded: security/privacy XML is assigned to the parent audit. |
| `/android/app/src/main/res/xml/diagnostics_file_paths.xml` | Excluded: security/privacy XML is assigned to the parent audit. |
