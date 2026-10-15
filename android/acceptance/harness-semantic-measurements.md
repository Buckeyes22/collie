# B09 semantic adapter measurements

This file records the capture recipes used for the bounded native additions. The captures and source
notes were already in the repository; B09 did not run vendor binaries or claim a new live session.
Version/date statements below refer to the capture corpus and the independent screen/journal lines in
`web/src/lib/harness/verified-versions.json`. Fixture replay is evidence for grammar and refusal
behavior, not for current vendor behavior on an installation that was not probed.

| Adapter | Versioned source | Native recipe added here | Deliberate raw/refusal cases |
| --- | --- | --- | --- |
| OMP | `omp--menu-resume*.txt` (17.2.12 captures, 2026-08) and `omp--v18-4-{resume,ask-single,ask-single-moved,ask-multi,ask-multi-checked,ask-note-editor,approval-bash,approval-write,approval-write-moved,approval-write-long,switch}*.txt` (18.4.10 corpus; switch walk measured in a sandbox pane without Enter on 2026-10-02); notes `web/src/lib/harness/omp/{RESUME,ASK,APPROVAL,SWITCH}_NOTES.md` | Exact boxed/bare resume session rows, single Ask with note-editor flow, checked multi-select with arrow/Space toggle read-back and explicit Enter submit, `Allow tool: bash` and `Allow tool: write` approvals (all path/content rows displayed, max 30 content rows), and session-only compact `/switch` model window. Pointer walks send measured Up/Down keys, then read the same session/question/options and pointed choice before Enter; checkbox writes additionally verify the requested checkbox alone changed. The multi-select `Other (type your own)` row remains visible but disabled until its version-matched editor flow is captured. Switch rows that are current, over-context, or visibly shortened remain walk positions but are never offered; search is cleared or closed with its one measured Escape. | Other approval types, `/model`, `/settings`, role/task model states and unsupported editor states remain raw. Multi-select screens with a preselected custom answer, and resume/switch selections whose row group, metadata, footer, detail rows, or pointer does not match the measured grammar stay raw. These are fixture-backed grammars; current installed-version parity, native live device replay, and a switch Enter on an isolated disposable pane remain pending. |
| Muse | `muse--ask-color*.txt`, `muse--ask-toppings*.txt`, `muse--trust-prompt.txt`, `muse--approval-ls*.txt`, `muse--draft-*.txt`; UI corpus version 1.3.0 captures and live recipe notes dated 2026-09-18; `web/src/lib/harness/muse/{question,checkbox,trust,approval,paste,chrome}.ts` | Exact question/checkbox footer plus live composer. Single-select and checkbox pointer walks use distinct digit/Enter choreography and same-question read-back. The measured checkbox focus marker is `›`; it is not interchangeable with a numbered list that lacks a captured pointer. In the captured checkbox-review phase, Submit is already pointed; Submit sends Enter, Interrupt first walks one row and requires pointer read-back, and Edit sends the measured Escape. Workspace trust and command approval are digit-only. Approval signature includes `Current argv:`. Native draft observation marks Muse `[Pasted Content N chars]` placeholders opaque so the phone cannot offer them for takeover. | Muse `/resume`, task drawer, multi-question flows and unmeasured approval/network variants remain raw/locked; review variants without one recognized pointer row remain raw. Direct send is bound only to the measured bare `Voice input / ❯ / rule / statusline` composer shape. The current installed Muse 1.4.4 has not been captured; 1.3.0 fixtures do not close current-version acceptance. |
| OpenCode | `oc--question--single*.txt`, question fixture corpus 1.18.31; screen canary 1.18.33 dated 2026-09-30; `web/src/lib/harness/opencode/QUESTION_NOTES.md` | Exact single-question footer, contiguous numbered rows and question text. Focus is accepted only from the measured focused SGR style (`106;145;198` on `30;30;30`) and must be unique; then the measured pointer walk is read back before Enter. | Overlay chrome has no bare row and is refused. Multi-question, multi-select, free-text continuation, answered/review, dismissed and malformed states remain raw. ANSI-stripped text alone never supplies a pointer. |

Native parser fixture coverage is pinned by `AgentSemanticParserTest.fixtureCorpusIsExhaustivelyAndExplicitlyClassified` and
`measuredOmpMuseAndOpenCodeDialogRecipesStayVersionAndShapeBound`. Interaction read-back is covered
by `SemanticInteractionEngineTest.measuredOmpPointerRecipeReadsBackBeforeSubmittingEnter` and
`measuredPointerRecipeDoesNotEnterAfterReadbackChangesTheOptionIdentity`, plus the Muse checkbox
toggle read-back tests. A live device replay,
TalkBack inspection, current installed-version probe and disposable guarded-send case remain pending.

## Existing screen evidence retained

- Claude screen 2.1.291 was captured 2026-10-06; journal 2.1.285 had a canary 2026-09-30. The
  current native parser is still intentionally narrower than all Claude UI paths: resume and effort
  pickers, unnumbered trust and clipped states stay raw/locked.
- Codex screen and journal 0.159.2 had a canary 2026-10-01. The first-prompt trust and approval
  guards remain fixture-backed; a live device key replay is still needed.
- AGY/Antigravity CLI 1.1.17 was captured 2026-08-26. The alias routes to the same parser; it is not
  independent per-kind detection or session evidence.
- Grok Build 1.0.41 capture dated 2026-09-27 is draft-only; prior dialog captures are from 1.0.5.
  This patch does not claim that version gap is closed.
- pi 0.87.1 and OpenCode 1.18.33 were canaried 2026-09-30. pi has no native block grammar here.
- Hermes has no verified executable version or SessionDB sweep; its `0.0.0` row is only a floor
  sentinel. Unknown vendors in the other 15 catalog rows have no versioned executable/grammar claim.

The PWA's exact adapter list remains `web/src/lib/harness/registry.ts`. Adding one of these native
recipes does not claim that the same surface works at every width, every version, or in every
terminal multiplexer. Unsupported captured variants keep the universal raw mirror and composer
write guard; no generic label matching creates a key recipe.
