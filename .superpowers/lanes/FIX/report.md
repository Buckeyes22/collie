# Lane FIX report — review findings on the Android pane UX plan

Branch `feat/android-twa` (dispatched at c773cc1). Six commits, one per item, each with the
mandated trailers; functional commits carry their CHANGELOG line in the same commit.

```
$ git log --oneline c773cc1..
7813bfd docs(changelog): dedupe lane merge
ec88947 test(android): the soft-wrap chrome test drives the real input-box branch
5a70fbf refactor(android): delete the Space overview card row instead of filtering it out
8afb7ff fix(android): the worktree sheet names the repository instead of posing as a base branch
ff0a8bc fix(android): Show terminal reserves the statusline height under the mirror again
44f7f44 fix(android): Load older prepends older turns instead of inverting the transcript
```

## Items

1. **Load older order — FIXED.** Red first (`olderEntriesPrependAboveTheExistingTurns` failed:
   a→appended below b), then `bind` inserts each new view at its entry's index offset by the
   Load-older child and preserves the offset on a prepend via a posted `scrollY + grown-height`
   adjustment. Green: TranscriptBodyViewTest 2/2.
2. **Stale mirror padding — FIXED.** Red first (`showTerminalRestoresTheStatuslinePaddingUnderTheMirror`
   failed: paddingBottom == 0), then the padding decision moved into `renderPaneBody` (mirror
   branch: `bottom = terminalStatusline.height`, `doOnLayout` when height is still 0; transcript
   branch: 0) and out of `renderTerminalContent`. Green: PaneActivityTest 62/62.
3. **Worktree caption — FIXED.** Red first (asserted new text), then `worktree_base_caption`
   → "In %1$s", new `worktree_base_unknown` = "Branches from the repository's current HEAD."
   (both in strings.xml), caption renders both lines. No `defaultBranch` invented. Green:
   DashboardShellActivityTest 8/8.
4. **Overview card deleted.** `SpaceListItem.Overview`, `OverviewHolder`, `TYPE_OVERVIEW`,
   the `ItemSpaceOverviewBinding` import, the emitter in `SpacePresentationModel.rows`, the
   three `filterNot` call sites, and `item_space_overview.xml` are gone;
   `worktree_create_new_branch` deleted. `grep -rn 'Overview\|worktree_create_new_branch' android/app/src`
   → no matches (exit 1). Forced touch outside the owned list, flagged here:
   `SpaceModelTest` pinned the Overview row in `rows()` output; its expectation now pins the
   Overview-free shape (red first, then green).
5. **Chrome filter test rebuilt.** `filterReflowsBodySoftWrapsButNotStatusRows` now uses the
   `"─".repeat(N)` box with a `❯` row that `locateInputBox` recognises, asserts the status row
   in `split(...).statusRows` and asserts the body does NOT contain `~/repo`. Note: this passed
   on its first run — the production `split` already peels the rows; the item fixed a weak test,
   and the new assertions fail if the box/status branch ever stops running (body would contain
   the status text and statusRows would be empty).
6. **CHANGELOG deduped.** Second copies of the two Web lines and three lane D lines removed
   from `## [Unreleased]`; the five lane C lines removed from `## [1.2.0]`; the four FIX lines
   moved to the end of Unreleased (landing order). Every Unreleased line now appears exactly
   once (section-local `sort | uniq -d` → 0 lines).

## Validation (final HEAD, exact output)

- GATE: `ANDROID_HOME=… JAVA_HOME=… android/gradlew -p android --no-daemon -q :app:testDebugUnitTest :app:lintDebug assembleDebug; echo exit=$?`
  → last lines `Wrote HTML report to …/lint-results-debug.html`, `GATE-exit=0`
- WEB: `cd web && bun run test -- --reporter=dot src/lib/harness`
  → `Test Files  25 passed (25)` / `Tests  2670 passed | 30 todo (2700)`
- WEBTYPES: `bun run typecheck && (cd web && bun run typecheck); echo exit=$?` → `WEBTYPES-exit=0`
- LINT: `bun run lint` → `LINT-exit=0` (0 warnings, 0 errors)
- `sort CHANGELOG.md | uniq -d | grep '^- '` → one line:
  `- Unauthenticated \`POST /pack/v1/enroll\` … (43b9a17)`.
  This duplicate is PRE-EXISTING: `git show 82fcfe7:CHANGELOG.md | grep -c …` → `3` (it appears
  three times across old numbered release sections, far below 1.2.0). Removing it means editing
  release sections this lane does not own ("Unreleased and 1.2.0 sections only"), so it was left
  and is named here. The lane-corruption duplicates themselves are gone: section-local check
  prints nothing, and `diff <(git show 82fcfe7:CHANGELOG.md | sed -n '/^## \[1.2.0\]/,/^## \[1.1/p') <(sed -n …)` → empty.
- `diff` for 1.2.0 vs 82fcfe7 → prints nothing (`1.2.0-identical`).

## Device walk — UNMET

`$HOME/Android/Sdk/platform-tools/adb connect 192.168.22.99:37363` → `failed to connect … Connection refused`
(tried twice, ~10s apart; `adb mdns services` still advertises `adb-R5CY31SPXMY-bo6iDx` at
192.168.22.99:37363, so the phone's wireless-debugging session needs re-arming on the device).
Per the lane contract the Pixel was NOT substituted. No §6 captures were taken, §6 was not
ticked, and the proposal's "Not run" note stands.

STATUS: INCOMPLETE
- Device walk (plan Task 23 Step 4): unmet — `adb connect 192.168.22.99:37363` refuses; the S25 Ultra's wireless debugging must be re-armed on the phone (screen on / toggle re-toggled), then re-run this lane's walk section.
