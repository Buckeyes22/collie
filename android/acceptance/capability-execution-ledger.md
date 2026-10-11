# Capability execution ledger

B41 kickoff ledger. Coverage is derived from the source registers below; the ranked scheduler and integration remain parent-owned.

## Snapshot and disposition

- Baseline: `e1923705807a83d7acac201a20953ade4d9613e3` in `/home/chris/git/collie`.
- B41 implementation: **in progress**; review: **pending**; acceptance: **pending**; closed: **no**.
- B01, B03 and B05 source integrated; current combined five native gates passed with 676 tests and no failures, and all 402 inputs held. B02 receipt validation awaits corrected compilation; B04 attempt29 is frozen with focused tests running. B06 native corrections follow independent whole review. B07 source is frozen pending review/gates; B08 authoring is active. Every functional acceptance item remains pending.
- InfernoDev is live-verified Windows11Pro build26200 x64; isolated CLI/Herdr0.9.3 version probes passed, functional Windows acceptance pending. User-authorized temporary Pixel USB ACL restored ADB on dell-7680; separate capabilitytest app preserves the existing debug app/data. Pixel9Pro Android17/API37 HTTPS smoke passed; AOSP LatinIME targeting and all51 physical interaction checks passed; independent fixture review and remaining acceptance stay pending.
- Existing broad audit evidence is bounded; its recorded physical/live, provider, proxy, signed-artifact, and Windows limitations remain open. Missing acceptance remains pending.

## Coverage inventory

| Register | Rows | Kickoff disposition |
|---|---:|---|
| Backlog items | 41 | Three independent implementation/review/acceptance states per item; no acceptance passed |
| Plan units | 75 | All 75 retain primary and joint closure ownership; acceptance pending |
| Original findings | 132 | 98 required, 33 baseline, 1 non-gap; no required finding accepted closed |
| Herdr schema methods | 102 | 17 source-register existing implementation mappings and 85 todo mappings; all runtime/review/acceptance evidence pending |

Each JSON row retains its source identity/line, owner mappings, three status fields, evidence slots, and limitations. A source citation, plan row, fixture, existing mapping, or broad audit does not itself establish feature acceptance.

## Coordinator-run baseline checks

The coordinator reports the focused bridge suite passed (657/0), native policy passed, root and web typechecks passed, and full-tree lint passed. Android unit/lint/debug/instrumentation/release build gates passed, as did real-TLS Crew integration (66/0). Broader baseline bridge (5406/0), CLI (1956/0), actual main script checks (294/0, 46 Windows skips), isolated shell checks, and web (20634 pass; 33 expected fail and 44 todo) passed. Snapshot tracking of local audit files changes the tracked privacy scan, so its authoritative check used the actual main index; ancestry fixtures used /var/tmp to avoid an existing unowned /tmp/.git. These are baseline/gate results, not live feature acceptance; the fake-Herdr three-node harness remains transport-only. The JSON records the source and scope for each report; detailed check handles remain in the parent-owned orchestration state.

## Ranked queue and readiness

| Rank | ID | Wave | Readiness inputs | Final completion dependencies | Joint integration dependencies | Implementation | Review | Acceptance | Evidence prep |
|---:|---|---:|---|---|---|---|---|---|---|
| 1 | B01 | 1 | — | — | B02, B03 | in_progress | bounded_foundation_passed_joint_review_pending | pending | not_started |
| 2 | B02 | 2 | B01 | B01 | — | corrective_authoring_R6_R7 | corrected4_sol_review_active_focused65_passed | pending | bounded_checks_passed_full_implementation_active |
| 3 | B03 | 2 | B01 | B01 | B21 | integrated_current_native_gates_passed_acceptance_pending | independent_whole_and_corrective_source_reviews_resolved | pending | partial_adapter_checks_passed_full_implementation_active |
| 4 | B04 | 2 | B01 | B01 | — | corrective_authoring_History_recovery_and_older_page_context | attempt30_source_review_passed_native_gates_pending | pending | source_and_dependency_snapshot_prepared |
| 5 | B05 | 2 | B01 | B01 | B04, B09 | integrated_current_native_gates_passed_acceptance_pending | independent_corrective_review_approved | pending | full_native_gates_passed |
| 6 | B06 | 2 | B01 | B01 | B09 | in_progress | compiler_corrective_frozen_native_focus_running | pending | source_snapshot_prepared_author_assigned |
| 7 | B07 | 2 | B01 | B01 | — | in_progress | whole_and_corrective_source_review_passed_native_full_running | pending | source_and_dependency_snapshot_prepared |
| 8 | B08 | 2 | B01 | B01 | — | in_progress | frozen_independent_whole_review_active | pending | isolated_source_snapshot_prepared |
| 9 | B09 | 3 | B01, B08 | B01, B08 | B05, B06, B19, B30, B34 | todo | pending | pending | prepared_pending_live_captures |
| 10 | B10 | 3 | B01, B04 | B01, B04 | — | todo | pending | pending | not_started |
| 11 | B11 | 2 | B01 | B01 | — | in_progress | authoring | pending | isolated_source_snapshot_prepared |
| 12 | B12 | 3 | B01, B03 | B01, B03 | — | todo | pending | pending | not_started |
| 13 | B13 | 4 | B01, B12 | B01, B12 | — | todo | pending | pending | not_started |
| 14 | B14 | 3 | B01, B02 | B01, B02 | B09, B15, B16 | todo | pending | pending | not_started |
| 15 | B15 | 5 | B01, B02, B13, B14 | B01, B02, B13, B14 | — | todo | pending | pending | not_started |
| 16 | B16 | 4 | B01, B02, B14 | B01, B02, B14 | — | todo | pending | pending | not_started |
| 17 | B17 | 3 | B01, B04 | B01, B04 | — | todo | pending | pending | not_started |
| 18 | B18 | 3 | B01, B08 | B01, B08 | — | todo | pending | pending | not_started |
| 19 | B19 | 4 | B01, B08, B09, B18 | B01, B08, B09, B18 | — | todo | pending | pending | not_started |
| 20 | B20 | 2 | B01 | B01 | — | todo | pending | pending | not_started |
| 21 | B21 | 3 | B01, B03 | B01, B03 | B09, B34 | todo | pending | pending | not_started |
| 22 | B22 | 2 | B01 | B01 | B23, B24, B27, B38 | todo | pending | pending | not_started |
| 23 | B23 | 2 | B01 | B01 | — | todo | pending | pending | not_started |
| 24 | B24 | 3 | B01, B23 | B01, B23 | B22, B40 | todo | pending | pending | not_started |
| 25 | B25 | 3 | B01, B02, B06, B22 | B01, B02, B06, B22 | — | todo | pending | pending | not_started |
| 26 | B26 | 2 | B01 | B01 | B27, B37 | todo | pending | pending | not_started |
| 27 | B27 | 3 | B01, B22, B26 | B01, B22, B26 | B37, B38 | todo | pending | pending | not_started |
| 28 | B28 | 2 | B01 | B01 | — | todo | pending | pending | not_started |
| 29 | B29 | 4 | B01, B12 | B01, B12 | B32 | todo | pending | pending | not_started |
| 30 | B30 | 4 | B01, B18 | B01, B18 | B09 | todo | pending | pending | not_started |
| 31 | B31 | 5 | B01, B18, B30 | B01, B18, B30 | — | todo | pending | pending | not_started |
| 32 | B32 | 6 | B01, B12, B30, B31 | B01, B12, B30, B31 | B29, B33 | todo | pending | pending | not_started |
| 33 | B33 | 7 | B01, B08, B12, B31, B32 | B01, B08, B12, B31, B32 | B37 | todo | pending | pending | not_started |
| 34 | B34 | 8 | B01, B03, B08, B09, B18, B33 | B01, B03, B08, B09, B18, B33 | B04, B09, B21, B37 | todo | pending | pending | not_started |
| 35 | B35 | 5 | B01, B30 | B01, B30 | — | todo | pending | pending | not_started |
| 36 | B36 | 6 | B01, B18, B30, B35 | B01, B18, B30, B35 | — | todo | pending | pending | not_started |
| 37 | B37 | 4 | B01, B22, B27 | B01, B22, B27 | B33, B34, B38 | todo | pending | pending | not_started |
| 38 | B38 | 5 | B01, B02, B22, B27, B37 | B01, B02, B22, B27, B37 | — | todo | pending | pending | not_started |
| 39 | B39 | 3 | B01, B04 | B01, B04 | — | todo | pending | pending | not_started |
| 40 | B40 | 2 | B01 | B01 | B24 | todo | pending | pending | not_started |
| 41 | B41 | 9 | B01, B02, B03, B04, B05, B06, B07, B08, B09, B10, B11, B12, B13, B14, B15, B16, B17, B18, B19, B20, B21, B22, B23, B24, B25, B26, B27, B28, B29, B30, B31, B32, B33, B34, B35, B36, B37, B38, B39, B40 | B01–B40 | — | in_progress | pending | pending | in_progress |

Implementation readiness inputs, final completion dependencies, and joint integration dependencies are separate in the JSON. Readiness evidence is recorded separately; B01 subset readiness does not imply full item approval, and dependency presence alone does not mark an item ready. The source `start_rule`, wave, module leases, conflict lists, and live-resource locks remain recorded per row.

## Plan-unit coverage

| Unit | Primary item | Joint closure items | Implementation | Review | Acceptance |
|---|---|---|---|---|---|
| C01 | B01 | B01, B03 | in_progress | pending | pending |
| C02 | B06 | B06 | todo | pending | pending |
| C03 | B04 | B04 | todo | pending | pending |
| C04 | B05 | B05 | todo | pending | pending |
| C05 | B11 | B11 | todo | pending | pending |
| C06 | B10 | B10 | todo | pending | pending |
| C07 | B10 | B10 | todo | pending | pending |
| C08 | B10 | B10 | todo | pending | pending |
| C09 | B14 | B14 | todo | pending | pending |
| C10 | B14 | B14 | todo | pending | pending |
| C11 | B15 | B15 | todo | pending | pending |
| C12 | B02 | B02 | in_progress | pending | pending |
| C13 | B16 | B16 | todo | pending | pending |
| C14 | B16 | B16 | todo | pending | pending |
| C15 | B17 | B17 | todo | pending | pending |
| C16 | B22 | B22 | todo | pending | pending |
| C17 | B20 | B20 | todo | pending | pending |
| C18 | B20 | B20 | todo | pending | pending |
| C19 | B26 | B26 | todo | pending | pending |
| C20 | B27 | B27, B38 | todo | pending | pending |
| C21 | B22 | B22, B27, B38 | todo | pending | pending |
| C22 | B07 | B07 | todo | pending | pending |
| C23 | B38 | B38 | todo | pending | pending |
| C24 | B25 | B25 | todo | pending | pending |
| C25 | B39 | B39 | todo | pending | pending |
| C26 | B39 | B39 | todo | pending | pending |
| C27 | B23 | B23 | todo | pending | pending |
| C28 | B28 | B28 | todo | pending | pending |
| C29 | B40 | B40 | todo | pending | pending |
| A00 | B01 | B01 | in_progress | pending | pending |
| A01 | B11 | B11 | todo | pending | pending |
| A02 | B10 | B10 | todo | pending | pending |
| A03 | B14 | B14 | todo | pending | pending |
| A04 | B15 | B15 | todo | pending | pending |
| A05 | B04 | B04 | todo | pending | pending |
| A06 | B14 | B14 | todo | pending | pending |
| A07 | B16 | B16 | todo | pending | pending |
| A08 | B26 | B26, B27 | todo | pending | pending |
| A09 | B20 | B20 | todo | pending | pending |
| A10 | B09 | B09, B34 | todo | pending | pending |
| A11 | B17 | B17 | todo | pending | pending |
| A12 | B07 | B07 | todo | pending | pending |
| A13 | B03 | B03, B21 | in_progress | pending | pending |
| A14 | B25 | B25 | todo | pending | pending |
| A15 | B22 | B22 | todo | pending | pending |
| A16 | B39 | B39 | todo | pending | pending |
| A17 | B05 | B05 | todo | pending | pending |
| A18 | B06 | B06 | todo | pending | pending |
| A19 | B39 | B39 | todo | pending | pending |
| A20 | B23 | B23 | todo | pending | pending |
| A21 | B28 | B28 | todo | pending | pending |
| H02 | B03 | B03 | in_progress | pending | pending |
| H03 | B03 | B03 | in_progress | pending | pending |
| H04 | B03 | B03 | in_progress | pending | pending |
| H05 | B12 | B12 | todo | pending | pending |
| H06 | B29 | B29, B32 | todo | pending | pending |
| H07 | B13 | B13 | todo | pending | pending |
| H08 | B15 | B15 | todo | pending | pending |
| H09 | B08 | B08 | todo | pending | pending |
| H10 | B19 | B19 | todo | pending | pending |
| H11 | B18 | B18 | todo | pending | pending |
| H12 | B09 | B09, B34 | todo | pending | pending |
| H13 | B30 | B30 | todo | pending | pending |
| H14 | B31 | B31 | todo | pending | pending |
| H15 | B32 | B32 | todo | pending | pending |
| H16 | B35 | B35 | todo | pending | pending |
| H17 | B36 | B36 | todo | pending | pending |
| H18 | B33 | B33, B37 | todo | pending | pending |
| H19 | B34 | B34 | todo | pending | pending |
| H20 | B41 | B41 | todo | pending | pending |
| R01 | B21 | B21 | todo | pending | pending |
| R02 | B24 | B24 | todo | pending | pending |
| W01 | B37 | B37, B38 | todo | pending | pending |
| W02 | B37 | B37 | todo | pending | pending |
| H01 | B01 | B01, B02 | in_progress | pending | pending |

Every plan unit has a source specification and line plus separate evidence fields in `/home/chris/git/collie/android/acceptance/capability-execution-ledger.json` → `plan_unit_coverage`.

## Original finding and method coverage

All 132 original findings are represented in `finding_coverage` in `/home/chris/git/collie/android/acceptance/capability-execution-ledger.json`, preserving the original finding ID, disposition, excerpt, implementation units, and backlog-item mapping.

All 102 shipped-schema method rows are represented in `herdr_method_coverage` in the same JSON. `existing_implementation_mapping` is the source register’s Collie mapping flag; it does not describe whether the method exists in the pinned vendor schema. The rows are the mapped members of that shipped schema. Runtime handshake/version/capability, adapter behavior, review, and acceptance remain distinct evidence fields.

## Environment preparation

Read-only discovery for B22, B24, B28, B37, and B38 is recorded at `/home/chris/git/collie/.project-docs/implementation-evidence/capability-backlog/B41/environment-preparation.md`. It records actual routes, outcomes, and outstanding acceptance limits.

Source registers: `/home/chris/git/collie/android/acceptance/capability-backlog.json`, `/home/chris/git/collie/android/acceptance/capability-backlog.md`, `/home/chris/git/collie/android/acceptance/capability-fix-plan.json`, and `/home/chris/git/collie/android/acceptance/capability-work-items/B41.md`.

