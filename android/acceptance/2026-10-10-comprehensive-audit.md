# Comprehensive native Android audit — 2026-10-10

The native application and its consumed Collie HTTP contracts have evidence-backed source dispositions. Confirmed locally correctable findings and regressions discovered during correction review have been fixed. The final validation manifest records the checks and artifact identities. This is a bounded source/contract audit with automated and emulator evidence, not universal compliance or verification of every real agent, device, provider or deployment.

## Coverage and traceability

| Inventory | Count and disposition |
|---|---|
| Android production inputs | 261: 92 Kotlin files, 168 resources including the debug name override, and one manifest. Every path has a source-review disposition and current SHA-256. Resource review is individual structural/accessibility/provenance review, not visual QA of every raster/glyph. |
| Build/configuration inputs | 10 reviewed files. Kotlin compiler/plugins/application libraries now align at 2.2.21 with AGP8.10.1 and R8 8.10.24; targeted toolchain lock refresh preserves unrelated application versions. |
| Tests/fixtures | 118 inventoried source/resource files, including nine instrumentation classes and the new reconciliation helper tests. The live physical-read unit remains blocked for actual execution; its unsafe setup and pairing prerequisites were corrected and compiled. |
| Documentation/tooling artifacts | 14, including five historical hardening artifacts excluded from claims about current runtime verification. |
| Documentation corpus | 13,235 inventoried documents, 122 source metadata groups and 123 actual collections. The installed-package source splits into server/web collections. Classification: 19 applicable, 55 partially applicable, 49 unrelated. All 74 applicable/partial collections map to bounded reviews of selected relevant references. **The 13,235 pages were not all reviewed.** Unused upstream SDK/product areas are explicitly excluded. |
| Native dependencies | 307 locked module/version records across configurations; 80 locked release runtime modules. Build/test dependencies, generated serializers/View Binding, bundled assets and R8-retained contents are distinguished. This is not an implementation audit of all third-party library source. |

The exact file ledger is `/home/chris/git/collie/android/acceptance/audit-file-ledger.json`. The collection mapping is `/home/chris/git/collie/android/acceptance/audit-collections.md` and `/home/chris/git/collie/android/acceptance/audit-collection-ledger.json`. Each points to the bounded reports with code paths, document sections, upstream URLs, pins/revisions, requirements, findings and test limits. `/home/chris/git/collie/android/acceptance/audit-parent-decisions.md` resolves interim findings and documents the final corrections. `/home/chris/git/collie/android/acceptance/audit-validation.json` supplies final execution and artifact evidence.

The archive project baseline is `8cc67d40d6195b5a5189d038ff2ec8409e0692de`; upstream is `632a2bf784045b1e2576da112d89ed21fecc9eeb`. Existing dirty source and the earlier targeted audit were preserved and reviewed as input. Earlier passing checks were not treated as comprehensive proof. Three explicitly configured `gpt-6-luna` agents received successive bounded units; parent independently checked findings, integrated production changes, corrected unsupported references and sent fixes back for review.

## Corrections

The C1–C11 rows in `/home/chris/git/collie/android/acceptance/audit-parent-decisions.md` cover the original findings and issues caught while reviewing their fixes:

- Codex acceptance is distinct from model dispatch. Accepted text survives unrelated mirror updates and Activity configuration changes, reconciles only against a later complete matching user turn, and supports explicit dismissal. It never automatically resends. Reconciliation also checks a turn polled while submission verification was still in progress.
- Pending media blocks button and keyboard submission. Completed Hands Free transcription clears its own busy gate before guarded submission. Accepted Codex text is scoped to the paired credential as well as origin.
- Missing-session guidance includes Hermes/OMP and preserves Muse's self-discovery exception. The four reported 44 dp controls use the application's 48 dp floor. Public standby status omits the unnecessary bearer.
- Already-cancelled launcher calls dismiss their confirmation; microphone permission completion rechecks current guards; stopping a pane cancels held-key callbacks and rejects a late release.
- Instrumentation smoke tests restore or isolate connection state. Keystore fixtures use separate preference storage and disposable keys. The live-read smoke requires an existing matching pairing, consistent with the current server's paired-read requirement.

No release version, TLS policy, encrypted credential format, native architecture, pairing-body diagnostic exclusion or write-authorization policy was weakened. The subsequent requested Kotlin/AGP alignment intentionally refreshes its toolchain lock entries and generated notices; unrelated application dependency versions are preserved. Changes remain local and uncommitted; nothing was pushed, deployed, published or released.

## Final verification

| Check | Result and evidence scope |
|---|---|
| Native JVM/Robolectric | 607 tests across 90 XML suites, zero failures/errors/skips, using the maintained Robolectric SDK 35 configuration. This simulator version does not simulate API 36. |
| Native build/lint | Debug/release lint and debug, instrumentation and minified unsigned release builds pass. Each lint report retains 179 warnings and zero errors; warning dispositions/limits are documented, not suppressed. |
| API 36 emulator | Final installed APKs run the 51-case synthetic transport/real Gboard interaction suite and the 10-case baseline/UI/Keystore suite. The baseline is explicitly run at 200% system font. Refer to the final manifest for exact completion and APK hashes. No real terminal session is used. |
| Bridge/CLI/scripts | Complete root test command passes: 5,406 bridge tests; 1,956 CLI passes/one Windows skip; 294 script passes/46 Windows skips; shell/packaging test groups pass. A clean fixture directory avoids an unrelated existing `/tmp/.git`. |
| Web | Both configured projects pass using the supported threads pool: 14,313 logic passes, 33 existing expected failures, 44 todo; 6,321 DOM passes. No test was deleted or reclassified. |
| Static/policy checks | Root and web TypeScript checks, full-tree oxlint, native policy including merged manifest, notices, version consistency, documentation links, license comparison and whitespace checks pass. |
| Isolated builds | CLI/server and PWA build successfully under `/tmp/collie-comprehensive-audit-server`. All 2,159 compared server/web/tooling input paths match the working checkout after copying the final alignment inputs into the isolated checkout. Running assets under `/home/chris/git/collie/bin/collie` and `/home/chris/git/collie/web/dist` were preserved. |
| Dependency/artifact evidence | Grype 0.116.1 reports zero matches for the 80-module locked-runtime SBOM with valid database built 2026-10-10T06:30:01Z. All five bundled license/notice texts occur byte-for-byte in the minified APK. This is not proof of absence of unknown vulnerabilities or a full APK-content SBOM. |

The initial baseline emulator attempt had two unmet 200% font prerequisites; the configured rerun supersedes it. A 51-case interaction attempt had one intermittent navigation assertion: the test observed Crew immediately after pressing Back. The same case passed individually without a source change; both that failure and the subsequent full-suite recheck are retained in the validation manifest. This remains evidence of test/navigation timing variability. An unintended subagent report write temporarily replaced the Robolectric configuration; parent restored its exact original contents, compared inventoried hashes and reran the complete gate. Interim failures and their causes remain documented. The final checks are associated with the final source hashes, rather than inferred from earlier artifacts.

The user-requested Kotlin fix is now implemented: compiler and serialization plugins plus application Kotlin libraries use 2.2.21, AGP uses 8.10.1 with bundled R8 8.10.24, and Gradle stays 8.11.1. The exact set is within the official compatibility ranges. `/home/chris/git/collie/android/acceptance/audit-kotlin-alignment.md` records the current implementation and final validation; the earlier 2.0.21/2.1.0 metadata investigation remains historical evidence.

## Remaining limitations and dispositions

- **Blocked runtime evidence:** actual paired physical-device/live read and real Codex queue/journal integration. No physical/live target was exercised, and no operator terminal writes were attempted. The physical-read test remains in scope with an honest blocked disposition.
- **Deliberate Codex boundary:** accepted text is in-memory and does not survive process death. Missing live baselines, legacy `/history`, commands, changed generations and transformed attachment turns may require manual dismissal. Exact newly journalled text provides correlation; it cannot prove causality without a server/agent message identifier. Empty composer means acceptance, not model dispatch.
- **Unperformed device/provider evidence:** physical radio/VPN changes, OEM Keystore invalidation/restore, real audio/provider delivery, battery measurements, production-signed artifact smoke and full audible TalkBack traversal. The emulator and source checks do not establish these behaviors.
- **Explicit exclusions:** native push delivery/registration is not shipped; unimplemented native Files/Changes/server-only UI routes are not API mismatches; Android does not implement Herdr sockets, vendor SDKs, Codex app-server or mux command protocols. Generated and third-party code is inventoried with appropriate provenance/build boundaries.

The workstation doclayer lookup was unanswerable and flagged stale indexing/sync failure and unavailable live verification. Its `path@cad2899a` evidence was not treated as live host proof; gap `gap-99e23283932f9d69d726` was recorded, and local tool checks supplied the emulator/JDK/SDK evidence. Details and flags are retained in the parent decisions report.

The Kotlin alignment follow-up also retains a 50/51 interaction attempt with an extra initial character in an injected text field. Its targeted recheck and complete unchanged 51-case rerun passed; the cause remains unproven. The current validation manifest distinguishes original execution from the current toolchain/APK evidence.
