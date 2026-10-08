# Android pane UX (transcript body, mirror floor, app-wide polish) Implementation Plan

**Present-day disposition — 2026-10-07:** This implementation plan is historical; its unchecked
task steps are not outstanding work. The native pane UX is implemented in `android/app/`, with
coverage in the Android unit tests. The later S25 Ultra re-walk at
[`android/acceptance/2026-09-11-ux-walk.md`](../../../android/acceptance/2026-09-11-ux-walk.md)
records 193 PASS and 0 FAIL for the changed screens, including transcript-first panes, switching
to the mirror for dialogs and Raw mode, returning to the transcript, and journalled `opencode`
panes. That walk supersedes the planned Task 23 checklist and the earlier 2026-09-10 ledger.
Implementation tasks 1–22 and the acceptance task are retained below as the dated execution
recipe and history; their unchecked boxes do not indicate current work. Use the current Android
source, tests, and acceptance record for present behavior. This disposition does not assert that
the historical gate commands have been rerun today.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the native Android pane show an agent's transcript as its body, fall back to a reflowed mirror only when the grid is the information, and close every design finding in Appendices A and B of the proposal.

**Architecture:** The pane body becomes a `TranscriptBodyView` fed by `GET /api/pane/:id/history` (polled at the pane's cadence, merged by uuid), rendered by the turn/part builders lifted out of `HistoryActivity` into a shared `HistoryTurnRenderer`. A `PaneBodyMode` decision (semantic surface up / journal unavailable / Raw on) picks mirror or transcript each render. The mirror itself gains soft-wrap reflow and a styled statusline row in `ClaudeChromeFilter` (native) and `chrome.ts` (web). Every other finding is a local change to the activity or builder that owns the screen.

**Tech Stack:** Kotlin, AndroidX, Material Components, ViewBinding, Robolectric unit tests (`:app:testDebugUnitTest`), Android lint; web side TypeScript + Vitest; bridge Bun.

**Spec:** `ANDROID_CLAUDE_PANE_UX_PROPOSAL.md` (§4 items 1–4, §5 invariants, §6 acceptance, Appendix A.1–A.12, Appendix B.1–B.7).

## Assumptions resolving §7 (the operator did not answer; these are the plan's decisions)

1. **Option 1 with option 3 as the floor** (§4 as written). Transcript body is the default for `claude`, `codex`, `pi`; the mirror floor lands first so it is correct wherever it still shows.
2. **Whole-body switch.** When a dialog, menu or autocomplete is up, the whole body becomes the mirror, exactly as the web does; the transcript is not shown above the dialog.
3. **Claude, Codex and Pi** all get the transcript body, because all three have journal adapters (`bridge/journal/registry.ts`). The decision is keyed on the history route's `available` flag, not on the agent name, so a fourth adapter needs no client change.

## Global Constraints

- Android is a native REST client: no WebView, Custom Tab, JavaScript bridge, or Android-only CORS exception (ADR 0036).
- No font picker and no font settings anywhere in the app (operator decision 2026-09-10). One bundled mono face for the mirror is a build asset, not a setting.
- No update banner or update actions outside `SettingsActivity`/`UpdatesActivity` (operator decision 2026-09-10).
- Every functional commit adds one line to `CHANGELOG.md` under `## [Unreleased]` in the same commit; never touch the three version files.
- Every user-facing string lives in `android/app/src/main/res/values/*.xml` (ADR 0037: English only, no locale overlays).
- No `oxlint-disable`; web changes pass `bun run lint` at the root.
- Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_011FBMGWi2QKXH5msmLFcQzu`.
- Branch: `feat/android-twa`. Never push to `main`.
- The Herdr read `revision` is always `0` (HERDR_API.md); every change-detection compares text, never revision alone.

## Gate commands (referenced by every task as GATE, WEB, BRIDGE)

```bash
# GATE — Android unit tests, lint, debug build. Expected: exit 0, no output from -q on success.
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  android/gradlew -p android --no-daemon -q :app:testDebugUnitTest :app:lintDebug assembleDebug; echo exit=$?

# ONE — a single Android test class. Expected: exit 0.
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  android/gradlew -p android --no-daemon -q :app:testDebugUnitTest --tests 'com.lateapex.collie.ui.<Class>'; echo exit=$?

# WEB — harness tests. Expected: last line "Test Files  N passed", exit 0.
cd web && bun run test -- --reporter=dot src/lib/harness | tail -5

# WEBTYPES — both typechecks. Expected: no output, exit 0.
bun run typecheck && (cd web && bun run typecheck); echo exit=$?

# LINT — Expected: "Found 0 warnings and 0 errors".
bun run lint | tail -2

# INSTALL — put the debug build on the S25 Ultra.
adb -s 192.0.2.10:37363 install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Lanes and file ownership

Four lanes can run in parallel worktrees. A file is owned by exactly one lane. `CHANGELOG.md` is the one shared file: every lane appends its own line under `## [Unreleased]` in each functional commit, and the integrator resolves the merge by keeping every line in landing order. Lanes never edit each other's files; a task that needs a string the other lane owns adds the string to its own values file (strings are per-file, not per-screen).

| Lane | Tasks | Owns (exclusive) |
| --- | --- | --- |
| A — pane | 1–12, 23 | `android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt`, `PaneViewModel.kt`, `HistoryActivity.kt`, `HistoryPresentation.kt`, new `HistoryTurnRenderer.kt`, new `TranscriptBodyView.kt`, new `PaneBodyMode.kt`, `ui/terminal/ClaudeChromeFilter.kt`, new `ui/terminal/SoftWrapReflow.kt`, `res/layout/activity_pane.xml`, `res/layout/activity_history.xml`, `res/values/pane_strings.xml`, `res/values/pane_terminal_parity.xml`, `res/values/pane_styles.xml`, `res/font/`, `res/raw/license_*.txt`, `res/raw/third_party_notices.txt`, `res/raw/keep.xml`, `res/drawable/bg_pane_*.xml`, `res/drawable/ic_composer_stop.xml`, `res/drawable/ic_attachment.xml`, tests `ui/PaneActivityTest.kt`, `ui/HistoryActivityTest.kt`, `ui/PaneLayoutTest.kt`, `ui/terminal/*`, new `ui/TranscriptBodyViewTest.kt`, `ui/PaneBodyModeTest.kt`, `ui/HistoryTurnRendererTest.kt` |
| B — web mirror | 13–14 | `web/src/lib/harness/claude/chrome.ts`, `chrome.test.ts`, new `web/src/lib/harness/claude/reflow.ts`, `reflow.test.ts`, `web/src/lib/harness/claude/index.ts`, `web/src/fixtures/panes/claude--*.txt` (new fixtures only) |
| C — dashboard, space, switcher | 15–19 | `SpaceActivity.kt`, `PaneAdapter.kt`, `AgentPresentation.kt`, `PaneSwitcher*.kt`, `MainActivity.kt`, `res/layout/activity_space.xml`, `res/layout/item_*.xml`, `res/values/dashboard_*.xml`, `res/values/strings.xml`, `res/drawable/ic_history_chevron_*.xml`, tests `ui/SpaceActivityTest.kt`, `ui/DashboardModelTest.kt`, `ui/DashboardShellActivityTest.kt`, `ui/PaneSwitcherModelTest.kt`, `ui/PaneHeaderPresentationTest.kt` |
| D — settings, updates | 20–22 | `SettingsActivity.kt`, `SettingsServerControls.kt`, `SettingsLocalPreferences.kt`, `UpdatesActivity.kt`, `res/layout/activity_settings.xml`, `res/values/settings_*.xml`, `res/values/update_native_parity.xml`, `res/values/settings_ids.xml`, tests `ui/SettingsActivityTest.kt`, `ui/UpdatesActivityParityTest.kt` |

Dependency order inside lane A is strict (each task builds on the previous). Lanes B, C, D are independent of A and of each other. Task 23 (device acceptance) runs after all lanes merge.

Each lane prompt is built from `~/.claude/templates/lane-prompt.md` with the ownership table above pasted into "You own" and every other row pasted into "Do not touch".

---

## Lane A — pane

### Task 1: Soft-wrap reflow for the mirror (native)

**Files:**
- Create: `android/app/src/main/java/com/lateapex/collie/ui/terminal/SoftWrapReflow.kt`
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/terminal/ClaudeChromeFilter.kt:13-42` (`filter`)
- Test: `android/app/src/test/java/com/lateapex/collie/ui/terminal/SoftWrapReflowTest.kt`

**Interfaces:**
- Produces: `object SoftWrapReflow { fun reflow(lines: List<String>, gridWidth: Int): List<String> }` and `fun gridWidth(lines: List<String>): Int`.
- Consumed by Task 2 (`ClaudeChromeFilter.filter`) and Task 3 (statusline row keeps its rows unreflowed).

The rule (proposal §4 item 3): a line whose visible length equals the grid width, followed by a line that starts with the same indentation as the first line's content start (or two spaces more, Claude's continuation indent), is joined to it with one space. A line is never joined when it, or the next line, is a table row (`│`, `┃`, `|` at column 0 or after indent), a box-drawing rule (`─`, `━`, `═`, `┌`, `└`, `├`), a code fence (` ``` `), a bullet start (`•`, `-`, `*`, `☐`, `☒`, `⎿`, `⏺`, digit-dot), or blank. The grid width is the maximum visible line length in the read.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.lateapex.collie.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class SoftWrapReflowTest {
    private val width = 40

    @Test
    fun joinsAFullWidthLineWithItsIndentedContinuation() {
        val first = "⏺ " + "x".repeat(38)
        val lines = listOf(first, "  continues here", "")
        assertEquals(listOf("$first continues here", ""), SoftWrapReflow.reflow(lines, width))
    }

    @Test
    fun doesNotJoinAShortLine() {
        val lines = listOf("⏺ short", "  next")
        assertEquals(lines, SoftWrapReflow.reflow(lines, width))
    }

    @Test
    fun neverJoinsTableRowsRulesFencesOrBullets() {
        val full = "│" + "a".repeat(38) + "│"
        val rule = "─".repeat(40)
        val fenceFull = "`".repeat(3) + "k".repeat(37)
        val bulletFull = "x".repeat(40)
        val lines = listOf(full, "│ b │", rule, "  c", fenceFull, "  code", bulletFull, "  • bullet", bulletFull, "  1. item")
        assertEquals(lines, SoftWrapReflow.reflow(lines, width))
    }

    @Test
    fun gridWidthIsTheLongestVisibleLine() {
        assertEquals(12, SoftWrapReflow.gridWidth(listOf("ab", "twelve chars", "")))
    }

    @Test
    fun joinsAcrossThreeRows() {
        val a = "y".repeat(40); val b = "  " + "z".repeat(38)
        assertEquals(listOf("$a ${b.trim()} tail"), SoftWrapReflow.reflow(listOf(a, b, "  tail"), width))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run ONE with `<Class>` = `terminal.SoftWrapReflowTest`. Expected: compilation error "Unresolved reference: SoftWrapReflow".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.lateapex.collie.ui.terminal

/**
 * Joins the soft wraps Herdr's grid imposed on a paragraph so the phone can wrap it once.
 * The grid width comes from the read itself: the longest visible row. A row is a wrap
 * candidate only when it fills that width exactly; anything Claude prints as structure
 * (tables, rules, fences, bullets, tree markers) is never joined in either direction.
 */
object SoftWrapReflow {
    private val structural = Regex("^\\s*(?:[│┃|┌└├┬┴┼─━═]|```|[•\\-*☐☒⎿⏺]\\s|\\d+\\.\\s)")

    fun gridWidth(lines: List<String>): Int = lines.maxOfOrNull { it.trimEnd().length } ?: 0

    fun reflow(lines: List<String>, gridWidth: Int): List<String> {
        if (gridWidth <= 0 || lines.size < 2) return lines
        val out = ArrayList<String>(lines.size)
        var index = 0
        while (index < lines.size) {
            var current = lines[index]
            var next = index + 1
            while (next < lines.size && canJoin(current, lines[next], gridWidth)) {
                current = current.trimEnd() + " " + lines[next].trim()
                next++
            }
            out.add(current)
            index = next
        }
        return out
    }

    private fun canJoin(current: String, next: String, gridWidth: Int): Boolean {
        val trimmed = current.trimEnd()
        if (trimmed.length < gridWidth) return false
        if (next.isBlank()) return false
        if (structural.containsMatchIn(trimmed) || structural.containsMatchIn(next)) return false
        val indent = next.length - next.trimStart().length
        return indent >= 2
    }
}
```

The "joinsAcrossThreeRows" case passes because after the first join `current` is longer than the width, so the check is `>=`: keep it `trimmed.length < gridWidth` returning false and `>=` continuing.

- [ ] **Step 4: Run test to verify it passes**

Run ONE `terminal.SoftWrapReflowTest`. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/terminal/SoftWrapReflow.kt android/app/src/test/java/com/lateapex/collie/ui/terminal/SoftWrapReflowTest.kt CHANGELOG.md
git commit -m "feat(android): reflow soft wraps in the mirror"
```
CHANGELOG line: `- Android: the pane mirror joins Herdr's soft wraps so a paragraph wraps once on the phone.`

### Task 2: Apply reflow in the chrome filter and pane render

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/terminal/ClaudeChromeFilter.kt:13-42` (`filter`), `:155-163` (`joinLines`)
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt` `renderTerminalContent` (~3200) where `ClaudeChromeFilter().filter(...)` is called
- Test: `android/app/src/test/java/com/lateapex/collie/ui/terminal/ClaudeChromeFilterTest.kt` (exists; add cases)

**Interfaces:**
- Consumes: `SoftWrapReflow.reflow`, `SoftWrapReflow.gridWidth` (Task 1).
- Produces: `ClaudeChromeFilter.filter(input: CharSequence, reflow: Boolean = true): CharSequence`. The `Line` list passed through `collapsePadding` is reflowed before `joinLines`; the status rows kept after the box are appended unreflowed.

- [ ] **Step 1: Write the failing test** (append to `ClaudeChromeFilterTest`)

```kotlin
    @Test
    fun filterReflowsBodySoftWrapsButNotStatusRows() {
        val width = 30
        val body1 = "⏺ " + "a".repeat(28)
        val body2 = "  tail"
        val box = listOf("╭" + "─".repeat(28) + "╮", "│ > " + " ".repeat(25) + "│", "╰" + "─".repeat(28) + "╯")
        val status = "  ~/repo main " + "·".repeat(16)
        val input = (listOf(body1, body2) + box + listOf(status)).joinToString("\n")
        val out = ClaudeChromeFilter().filter(input).toString().lines()
        assertEquals("$body1 tail", out[0])
        assertEquals(status, out.last())
    }

    @Test
    fun filterWithReflowOffKeepsRows() {
        val body1 = "⏺ " + "a".repeat(28)
        val input = listOf(body1, "  tail").joinToString("\n")
        assertEquals(input, ClaudeChromeFilter().filter(input, reflow = false).toString())
    }
```

- [ ] **Step 2: Run** ONE `terminal.ClaudeChromeFilterTest`. Expected: FAIL (`filterReflowsBodySoftWrapsButNotStatusRows` assertion on `out[0]`; second test fails to compile on the named argument).

- [ ] **Step 3: Implement**

In `ClaudeChromeFilter`:

```kotlin
    fun filter(input: CharSequence, reflow: Boolean = true): CharSequence {
        val lines = splitLines(input)
        var end = lines.size
        while (end > 0 && lines[end - 1].text.isBlank()) end--
        if (end == 0) return input
        val width = SoftWrapReflow.gridWidth(lines.map { it.text })

        val box = locateInputBox(lines, end) ?: run {
            val collapsed = reflowLines(collapsePadding(lines), width, reflow)
            return if (collapsed == lines) input else joinLines(input, collapsed)
        }
        var bodyEnd = box.top
        while (bodyEnd > 0 && lines[bodyEnd - 1].text.isBlank()) bodyEnd--

        val kept = buildList {
            addAll(reflowLines(collapsePadding(lines.subList(0, bodyEnd)), width, reflow))
            for (index in box.bottomBorder + 1 until box.statusEnd) {
                if (lines[index].text.isNotBlank()) add(lines[index])
            }
        }
        return joinLines(input, kept)
    }

    /** A joined row keeps the span of its first source row; the joined text is carried in [Line.text]. */
    private fun reflowLines(body: List<Line>, width: Int, enabled: Boolean): List<Line> {
        if (!enabled) return body
        val texts = SoftWrapReflow.reflow(body.map { it.text }, width)
        if (texts.size == body.size) return body
        val out = ArrayList<Line>(texts.size)
        var source = 0
        for (text in texts) {
            val first = body[source]
            var consumed = 1
            var acc = first.text.trimEnd()
            while (acc != text && source + consumed < body.size) {
                acc = acc + " " + body[source + consumed].text.trim(); consumed++
            }
            out.add(Line(first.start, body[source + consumed - 1].end, text))
            source += consumed
        }
        return out
    }
```

Then change `joinLines` so a `Line` whose `text` differs from `input.subSequence(start, end)` is emitted as its `text` with the spans of its first source row copied (use `SpannableStringBuilder.append(CharSequence)` of the source subsequence for the first row, then `append(" ")`, then the trimmed subsequence of each continuation row, so colour spans survive). Implement by keeping, per joined line, the list of source rows; simplest is to make `Line` carry `sources: List<Line> = listOf(this)` and let `joinLines` iterate `sources`.

In `PaneActivity.renderTerminalContent`, the filter call passes `reflow = !wrapLinesSwitch.isChecked.not()`; concretely: `ClaudeChromeFilter().filter(raw, reflow = displayPreferences.getBoolean(PREF_WRAP, true))` so a user who turned Wrap off (pan mode) sees the grid untouched.

- [ ] **Step 4: Run** ONE `terminal.ClaudeChromeFilterTest` then GATE. Expected: exit=0 both.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/terminal/ClaudeChromeFilter.kt android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt android/app/src/test/java/com/lateapex/collie/ui/terminal/ClaudeChromeFilterTest.kt CHANGELOG.md
git commit -m "feat(android): reflow the Claude mirror body through the chrome filter"
```
CHANGELOG line: `- Android: the Claude chrome filter reflows the transcript body and leaves status rows and tables untouched.`

### Task 3: Statusline chrome row under the mirror

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/terminal/ClaudeChromeFilter.kt` (add `statusRows`)
- Modify: `android/app/src/main/res/layout/activity_pane.xml:248-330` (add `terminal_statusline` under `terminal_content`, inside `terminal_surface`)
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt` `renderTerminalContent`
- Modify: `android/app/src/main/res/values/pane_terminal_parity.xml` (`pane_statusline_description`)
- Test: `android/app/src/test/java/com/lateapex/collie/ui/terminal/ClaudeChromeFilterTest.kt`, `android/app/src/test/java/com/lateapex/collie/ui/PaneActivityTest.kt`

**Interfaces:**
- Produces: `data class ChromeSplit(val body: CharSequence, val statusRows: List<CharSequence>)` and `fun ClaudeChromeFilter.split(input: CharSequence, reflow: Boolean = true): ChromeSplit`. `filter` becomes `split(...).body` plus the rows, so existing callers keep working; the pane uses `split`.
- Layout: `TextView` id `terminal_statusline`, `visibility=gone`, single line per status row separated by ` · `, `textAppearance` 11sp, background `@drawable/bg_pane_status_band`, `fontFamily` `@font/collie_mono` (Task 4 supplies the font; until then `monospace`).

- [ ] **Step 1: Failing tests**

```kotlin
    @Test
    fun splitPeelsStatusRowsOffTheBody() {
        val body = "⏺ hello"
        val box = listOf("╭" + "─".repeat(28) + "╮", "│ > " + " ".repeat(25) + "│", "╰" + "─".repeat(28) + "╯")
        val status1 = "  Opus 5 · 12% ctx"; val status2 = "  ~/repo main"
        val split = ClaudeChromeFilter().split((listOf(body) + box + listOf(status1, status2)).joinToString("\n"))
        assertEquals(body, split.body.toString())
        assertEquals(listOf(status1.trim(), status2.trim()), split.statusRows.map { it.toString() })
    }
```
And in `PaneActivityTest` (use the existing `paneIntent` helper and the state-injection pattern the file already uses for `PaneReadResponse`):

```kotlin
    @Test
    fun statuslineRowRendersUnderTheMirrorForClaude() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("status:pane", agent = "claude")).create().start().resume().get()
        val text = listOf("⏺ hi", "╭" + "─".repeat(28) + "╮", "│ > " + " ".repeat(25) + "│", "╰" + "─".repeat(28) + "╯", "  Opus 5 · 12%").joinToString("\n")
        activity.renderForTest(PaneReadResponse("status:pane", text, truncated = false, revision = 0))
        val row = activity.findViewById<TextView>(R.id.terminal_statusline)
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals("Opus 5 · 12%", row.text.toString())
    }
```
`paneIntent` currently hardcodes `EXTRA_AGENT = "opencode"`; add an `agent: String = "opencode"` parameter. `renderForTest` is the `@VisibleForTesting internal fun` the file already exposes for feeding a pane read; if it is named differently, use that name and do not add a second one.

- [ ] **Step 2: Run** ONE `terminal.ClaudeChromeFilterTest` and ONE `PaneActivityTest`. Expected: FAIL (unresolved `split`, `terminal_statusline`).

- [ ] **Step 3: Implement**

Filter:
```kotlin
    data class ChromeSplit(val body: CharSequence, val statusRows: List<CharSequence>)

    fun split(input: CharSequence, reflow: Boolean = true): ChromeSplit {
        val lines = splitLines(input)
        var end = lines.size
        while (end > 0 && lines[end - 1].text.isBlank()) end--
        if (end == 0) return ChromeSplit(input, emptyList())
        val width = SoftWrapReflow.gridWidth(lines.map { it.text })
        val box = locateInputBox(lines, end) ?: run {
            val collapsed = reflowLines(collapsePadding(lines), width, reflow)
            return ChromeSplit(if (collapsed == lines) input else joinLines(input, collapsed), emptyList())
        }
        var bodyEnd = box.top
        while (bodyEnd > 0 && lines[bodyEnd - 1].text.isBlank()) bodyEnd--
        val body = joinLines(input, reflowLines(collapsePadding(lines.subList(0, bodyEnd)), width, reflow))
        val rows = (box.bottomBorder + 1 until box.statusEnd)
            .map { lines[it] }.filter { it.text.isNotBlank() }
            .map { input.subSequence(it.start, it.end).trim() }
        return ChromeSplit(body, rows)
    }

    fun filter(input: CharSequence, reflow: Boolean = true): CharSequence = split(input, reflow).body
```
Note `filter` no longer appends status rows to the body; update `ClaudeChromeFilterTest` cases that expected them (they now assert on `split(...).statusRows`).

Layout, directly after the `terminal_content` FrameLayout closes and before `terminal_surface` closes (it must sit outside `terminal_scroll` so it is pinned):
```xml
        <TextView
            android:id="@+id/terminal_statusline"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom"
            android:background="@drawable/bg_pane_status_band"
            android:contentDescription="@string/pane_statusline_description"
            android:ellipsize="end"
            android:fontFamily="monospace"
            android:maxLines="2"
            android:paddingHorizontal="12dp"
            android:paddingVertical="4dp"
            android:textColor="@color/collie_muted"
            android:textSize="11sp"
            android:visibility="gone" />
```
String: `<string name="pane_statusline_description">Agent status line</string>`.

`PaneActivity.renderTerminalContent`: replace the `filter(...)` call with `split(...)`; set `binding.terminalStatusline.text = split.statusRows.joinToString(" · ")` and `isVisible = split.statusRows.isNotEmpty() && !rawTerminal` (the Raw switch shows the grid verbatim, so the row hides there). Add `terminalScroll` bottom padding equal to the row's measured height via `terminalStatusline.doOnLayout { terminalScroll.updatePadding(bottom = it.height) }` so the last mirror row is never covered.

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/terminal/ClaudeChromeFilter.kt android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt android/app/src/main/res/layout/activity_pane.xml android/app/src/main/res/values/pane_terminal_parity.xml android/app/src/test/java/com/lateapex/collie/ui/terminal/ClaudeChromeFilterTest.kt android/app/src/test/java/com/lateapex/collie/ui/PaneActivityTest.kt CHANGELOG.md
git commit -m "feat(android): render Claude's statusline as a chrome row under the mirror"
```
CHANGELOG line: `- Android: the rows under Claude's input box render as one pinned status row instead of raw mono lines.`

### Task 4: Bundled mono face with box-drawing coverage (B.2)

**Files:**
- Create: `android/app/src/main/res/font/jetbrains_mono_regular.ttf`, `android/app/src/main/res/font/collie_mono.xml`, `android/app/src/main/res/raw/license_jetbrains_mono.txt`
- Modify: `android/app/src/main/res/raw/third_party_notices.txt`, `android/app/src/main/res/raw/keep.xml`
- Modify: `android/app/src/main/res/values/pane_styles.xml` (`Widget.Collie.PaneKey` `android:fontFamily` → `@font/collie_mono`)
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt` `applyTerminalPreferences` (`Typeface.MONOSPACE` → `ResourcesCompat.getFont(this, R.font.collie_mono)`)
- Modify: `android/app/src/main/res/layout/activity_pane.xml` (`terminal_text`, `terminal_statusline` `fontFamily="@font/collie_mono"`)
- Test: `android/app/src/test/java/com/lateapex/collie/ui/PaneLayoutTest.kt`

**Interfaces:** `R.font.collie_mono` is the only mono reference in the app after this task. This is a build asset, not a setting: no picker, no preference, no Settings row.

Source: JetBrains Mono 2.304 Regular, OFL 1.1, from `https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip` (`fonts/ttf/JetBrainsMono-Regular.ttf`). It covers U+2500–U+257F box drawing and U+23BF (`⎿`), the glyph the system face draws wrong.

- [ ] **Step 1: Failing test** (append to `PaneLayoutTest`)

```kotlin
    @Test
    fun mirrorUsesTheBundledMonoFace() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("font:pane")).create().get()
        val expected = androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.collie_mono)
        assertSame(expected, activity.findViewById<TextView>(R.id.terminal_text).typeface)
    }
```

- [ ] **Step 2: Run** ONE `PaneLayoutTest`. Expected: FAIL, unresolved `R.font.collie_mono`.

- [ ] **Step 3: Implement**

```bash
export COLLIE_REPO=/path/to/collie  # your checkout
cd /tmp && curl -sSLO https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip \
  && unzip -o -q JetBrainsMono-2.304.zip 'fonts/ttf/JetBrainsMono-Regular.ttf' 'OFL.txt' \
  && cp fonts/ttf/JetBrainsMono-Regular.ttf "$COLLIE_REPO"/android/app/src/main/res/font/jetbrains_mono_regular.ttf \
  && cp OFL.txt "$COLLIE_REPO"/android/app/src/main/res/raw/license_jetbrains_mono.txt
```
`res/font/collie_mono.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<font-family xmlns:android="http://schemas.android.com/apk/res/android">
    <font android:font="@font/jetbrains_mono_regular" android:fontStyle="normal" android:fontWeight="400" />
</font-family>
```
Append to `third_party_notices.txt` a block mirroring the Aldrich entry: name, version 2.304, copyright "2020 The JetBrains Mono Project Authors", licence pointer `license_jetbrains_mono.txt`. Add `@raw/license_jetbrains_mono` to `keep.xml`'s `tools:keep` list.

`applyTerminalPreferences`: `val mono = ResourcesCompat.getFont(this, R.font.collie_mono) ?: Typeface.MONOSPACE; terminalText.typeface = mono; terminalStatusline.typeface = mono` and wherever `Typeface.MONOSPACE` is set on block views in `renderTerminalBlocks`.

- [ ] **Step 4: Run** GATE. Expected: exit=0 (lint checks the licence file is referenced by `keep.xml`).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/res/font android/app/src/main/res/raw android/app/src/main/res/values/pane_styles.xml android/app/src/main/res/layout/activity_pane.xml android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt android/app/src/test/java/com/lateapex/collie/ui/PaneLayoutTest.kt CHANGELOG.md
git commit -m "feat(android): bundle JetBrains Mono as the mirror face"
```
CHANGELOG line: `- Android: the mirror and key caps use a bundled JetBrains Mono so tree markers and box drawing render correctly.`

### Task 5: Lift the transcript renderer out of HistoryActivity

**Files:**
- Create: `android/app/src/main/java/com/lateapex/collie/ui/HistoryTurnRenderer.kt`
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/HistoryActivity.kt:430-700` (`turnView`, `roleHeader`, `partView`, `markdownView`, `toolView` move out; `HistoryActivity` calls the renderer)
- Test: `android/app/src/test/java/com/lateapex/collie/ui/HistoryTurnRendererTest.kt`, existing `HistoryActivityTest` must stay green

**Interfaces:**
- Produces:
```kotlin
internal class HistoryTurnRenderer(
    private val context: Context,
    private val agent: String,
    private val expandedTools: MutableSet<Pair<String, Int>>,
    private val onToolToggle: (uuid: String, partIndex: Int) -> Unit,
    private val registerTextTarget: (HistorySearchKey, HistoryTextTarget) -> Unit = { _, _ -> },
) {
    fun turnView(turn: HistoryTurn, showHeader: Boolean): View
}
```
Every private builder (`roleHeader`, `partView`, `markdownView`, `toolView`, the markdown block builders, `dp`, `color`) moves verbatim into this class with `this@HistoryActivity` replaced by `context`. `HistoryActivity` keeps a `private val renderer by lazy { HistoryTurnRenderer(this, intent.getStringExtra(EXTRA_AGENT).orEmpty(), expandedTools, ::toggleTool, historyTextTargets::put) }` and `renderEntries` calls `renderer.turnView(...)`.

- [ ] **Step 1: Failing test**

```kotlin
@RunWith(RobolectricTestRunner::class)
class HistoryTurnRendererTest {
    @Test
    fun rendersAUserTurnWithCardBackgroundAndProse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val turn = HistoryPresentation.turn(
            TranscriptEntry("u1", "2026-09-10T00:00:00Z", "user", listOf(TranscriptPart(kind = "text", text = "hello"))),
            agent = "claude", resources = context.resources,
        )
        val view = HistoryTurnRenderer(context, "claude", mutableSetOf(), { _, _ -> }).turnView(turn, showHeader = true)
        val content = (view as ViewGroup).getChildAt(0) as ViewGroup
        assertNotNull(content.background)
        val texts = generateSequence(0) { it + 1 }.take(content.childCount).map { content.getChildAt(it) }
            .filterIsInstance<ViewGroup>().flatMap { g -> (0 until g.childCount).map { g.getChildAt(it) } }
            .filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(texts.any { it.contains("hello") })
    }
}
```

- [ ] **Step 2: Run** ONE `HistoryTurnRendererTest`. Expected: FAIL, unresolved `HistoryTurnRenderer`.

- [ ] **Step 3: Implement** the move described in Interfaces. Do not change any builder's output; this is a pure extraction. `HistoryActivity.toggleTool(uuid, index)` is the existing click handler body that flips `expandedTools` and calls `renderEntries()`.

- [ ] **Step 4: Run** ONE `HistoryTurnRendererTest`, ONE `HistoryActivityTest`, ONE `HistoryHighlightTest`. Expected: exit=0 for all three.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/HistoryTurnRenderer.kt android/app/src/main/java/com/lateapex/collie/ui/HistoryActivity.kt android/app/src/test/java/com/lateapex/collie/ui/HistoryTurnRendererTest.kt CHANGELOG.md
git commit -m "refactor(android): lift the transcript turn renderer out of HistoryActivity"
```
CHANGELOG line: `- Android: the history turn renderer is a reusable class so the pane body can share it.`

### Task 6: Transcript polling in PaneViewModel

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneViewModel.kt:45-70` (`PaneUiState`), `:134-156` (`startPolling`/`refresh`), `:611` (`load`)
- Test: `android/app/src/test/java/com/lateapex/collie/ui/PaneViewModelTest.kt` (its `FakeApi` at line 501 gains a `history` override)

**Interfaces:**
- Produces on `PaneUiState`:
```kotlin
    val transcript: List<TranscriptEntry> = emptyList(),
    val transcriptAvailable: Boolean? = null,   // null until the first history answer
    val transcriptReason: String? = null,
    val transcriptHasMore: Boolean = false,
```
and `fun loadOlderTranscript()` on the view model. The transcript is fetched in the same poll tick as the pane read, after it, with `repository.history(address, limit = 60)`; newer entries are merged with `HistoryPresentation.mergeOlder(current = state.transcript, older = page.entries)` reversed for the newest side: implement `mergeNewer(current, newer) = (current + newer).distinctBy { it.uuid }`, added to `HistoryPresentation` next to `mergeOlder`. When `available == false` the state records `transcriptAvailable = false, transcriptReason = reason` and polling of history stops for that pane (one answer is enough; the mirror shows).

- [ ] **Step 1: Failing test**

```kotlin
    @Test
    fun pollMergesTranscriptEntriesByUuid() = runTest {
        val api = FakeApi(historyPages = ArrayDeque(listOf(
            PaneHistoryResponse("p", available = true, entries = listOf(entry("a"), entry("b"))),
            PaneHistoryResponse("p", available = true, entries = listOf(entry("b"), entry("c"))),
        )))
        val model = viewModel(api)
        model.refresh(); advanceUntilIdle()
        model.refresh(); advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), model.state.value.transcript.map { it.uuid })
        assertEquals(true, model.state.value.transcriptAvailable)
    }

    @Test
    fun unavailableHistoryIsRecordedOnceAndNotPolledAgain() = runTest {
        val api = FakeApi(historyPages = ArrayDeque(listOf(PaneHistoryResponse("p", available = false, reason = "no-session"))))
        val model = viewModel(api)
        model.refresh(); advanceUntilIdle()
        model.refresh(); advanceUntilIdle()
        assertEquals(false, model.state.value.transcriptAvailable)
        assertEquals("no-session", model.state.value.transcriptReason)
        assertEquals(1, api.historyCalls)
    }
```
`entry(uuid)` = `TranscriptEntry(uuid, "2026-09-10T00:00:00Z", "assistant", listOf(TranscriptPart("text", text = uuid)))`. `viewModel(api)` is the file's existing factory helper (it builds a `CollieRepository` over `FakeStore`/`FakeApi`); extend `FakeApi` with `val historyPages: ArrayDeque<PaneHistoryResponse> = ArrayDeque()`, `var historyCalls = 0`, and:
```kotlin
        override suspend fun history(connection: Connection, address: PaneAddress, limit: Int, before: String?): PaneHistoryResponse {
            historyCalls++
            return historyPages.removeFirstOrNull() ?: PaneHistoryResponse(address.paneId, available = true)
        }
```
(match the exact `CollieApi.history` signature at `network/CollieApiClient.kt:76`).

- [ ] **Step 2: Run** ONE `PaneViewModelTest`. Expected: FAIL, unresolved `transcript`.

- [ ] **Step 3: Implement**

In `load()`, after the `ApiResult.Success` branch assigns `pane`, call `loadTranscript()`:
```kotlin
    private var transcriptKnownUnavailable = false

    private suspend fun loadTranscript() {
        if (transcriptKnownUnavailable) return
        when (val result = repository.history(address, limit = TRANSCRIPT_PAGE)) {
            is ApiResult.Success -> {
                val page = result.value
                if (!page.available) {
                    transcriptKnownUnavailable = true
                    mutableState.value = mutableState.value.copy(transcriptAvailable = false, transcriptReason = page.reason, transcript = emptyList())
                    return
                }
                mutableState.value = mutableState.value.copy(
                    transcript = HistoryPresentation.mergeNewer(mutableState.value.transcript, page.entries),
                    transcriptAvailable = true,
                    transcriptReason = null,
                    transcriptHasMore = page.hasMore,
                )
            }
            is ApiResult.Failure, is ApiResult.NotModified -> Unit
        }
    }

    fun loadOlderTranscript() {
        val oldest = mutableState.value.transcript.firstOrNull()?.uuid ?: return
        viewModelScope.launch {
            val result = repository.history(address, limit = TRANSCRIPT_PAGE, before = oldest)
            if (result is ApiResult.Success && result.value.available) {
                mutableState.value = mutableState.value.copy(
                    transcript = HistoryPresentation.mergeOlder(mutableState.value.transcript, result.value.entries),
                    transcriptHasMore = result.value.hasMore,
                )
            }
        }
    }

    companion object { const val TRANSCRIPT_PAGE = 60 }
```
`HistoryPresentation.mergeNewer`:
```kotlin
    fun mergeNewer(current: List<TranscriptEntry>, newer: List<TranscriptEntry>): List<TranscriptEntry> {
        val seen = HashSet<String>(current.size + newer.size)
        return (current + newer).filter { seen.add(it.uuid) }
    }
```
The bridge route pages newest-anchored (`bridge/journal/store.ts pageEntries`), so `page.entries` without `before` is always the newest 60; merging keeps older entries the phone already holds.

- [ ] **Step 4: Run** ONE `PaneViewModelTest`, ONE `HistoryPresentationTest`. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/PaneViewModel.kt android/app/src/main/java/com/lateapex/collie/ui/HistoryPresentation.kt android/app/src/test/java/com/lateapex/collie/ui/PaneViewModelTest.kt CHANGELOG.md
git commit -m "feat(android): poll the pane transcript beside the mirror read"
```
CHANGELOG line: `- Android: the pane view model polls the journal transcript with the mirror and merges entries by uuid.`

### Task 7: PaneBodyMode decision

**Files:**
- Create: `android/app/src/main/java/com/lateapex/collie/ui/PaneBodyMode.kt`
- Test: `android/app/src/test/java/com/lateapex/collie/ui/PaneBodyModeTest.kt`

**Interfaces:**
```kotlin
enum class PaneBody { TRANSCRIPT, MIRROR }
enum class PaneBodyReason { NONE, DIALOG, NO_JOURNAL, RAW, PENDING }

data class PaneBodyMode(val body: PaneBody, val reason: PaneBodyReason)

object PaneBodyDecision {
    fun decide(
        surface: SemanticSurface?,
        transcriptAvailable: Boolean?,
        rawTerminal: Boolean,
        agent: String?,
    ): PaneBodyMode
}
```
Rules (§4 item 2): `rawTerminal` → MIRROR/RAW. `surface != null` → MIRROR/DIALOG (every `SemanticKind` counts, including `AUTOCOMPLETE`). `agent` is `shell` or blank → MIRROR/NO_JOURNAL. `transcriptAvailable == false` → MIRROR/NO_JOURNAL. `transcriptAvailable == null` → MIRROR/PENDING (first paint before the history answer). Otherwise TRANSCRIPT/NONE.

- [ ] **Step 1: Failing test**

```kotlin
class PaneBodyModeTest {
    private val dialog = SemanticSurface(SemanticKind.PROMPT_SELECT, "q", regionSignature = "s", revision = 0, startLine = 0, endLine = 1)

    @Test fun rawWins() = assertEquals(PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.RAW), PaneBodyDecision.decide(dialog, true, rawTerminal = true, agent = "claude"))
    @Test fun dialogForcesMirror() = assertEquals(PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.DIALOG), PaneBodyDecision.decide(dialog, true, false, "claude"))
    @Test fun shellHasNoJournal() = assertEquals(PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.NO_JOURNAL), PaneBodyDecision.decide(null, true, false, "shell"))
    @Test fun unavailableHistoryIsMirror() = assertEquals(PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.NO_JOURNAL), PaneBodyDecision.decide(null, false, false, "codex"))
    @Test fun pendingIsMirror() = assertEquals(PaneBodyMode(PaneBody.MIRROR, PaneBodyReason.PENDING), PaneBodyDecision.decide(null, null, false, "pi"))
    @Test fun otherwiseTranscript() = assertEquals(PaneBodyMode(PaneBody.TRANSCRIPT, PaneBodyReason.NONE), PaneBodyDecision.decide(null, true, false, "claude"))
}
```

- [ ] **Step 2: Run** ONE `PaneBodyModeTest`. Expected: FAIL, unresolved.

- [ ] **Step 3: Implement** exactly the rules above as a `when` in that order.

- [ ] **Step 4: Run** ONE `PaneBodyModeTest`. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/PaneBodyMode.kt android/app/src/test/java/com/lateapex/collie/ui/PaneBodyModeTest.kt CHANGELOG.md
git commit -m "feat(android): decide the pane body from surface, journal and raw switch"
```
CHANGELOG line: `- Android: one rule picks transcript or mirror for the pane body.`

### Task 8: TranscriptBodyView and the body switch row in PaneActivity

**Files:**
- Create: `android/app/src/main/java/com/lateapex/collie/ui/TranscriptBodyView.kt`
- Modify: `android/app/src/main/res/layout/activity_pane.xml:248-330` (`terminal_surface` gains `transcript_body` and `body_mode_row`)
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt` `render()` (~1296), `renderTerminalContent` (~3200), `showDrawer` (~2085), `openHistory` (3339)
- Modify: `android/app/src/main/res/values/pane_strings.xml`
- Test: `android/app/src/test/java/com/lateapex/collie/ui/TranscriptBodyViewTest.kt`, `PaneActivityTest.kt`

**Interfaces:**
```kotlin
class TranscriptBodyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var onLoadOlder: (() -> Unit)? = null
    fun bind(agent: String, entries: List<TranscriptEntry>, hasMore: Boolean)
}
```
Internals: a `ScrollView` → `LinearLayout` with a "Load older" `MaterialButton` (id `transcript_load_older`, text `@string/pane_transcript_load_older`) at the top, then one `HistoryTurnRenderer.turnView` per entry keyed by uuid in a `linkedMapOf<String, View>` so a re-bind only adds views for new uuids (same trick `HistoryActivity.turnViews` uses). `bind` keeps the scroll at the bottom when it was at the bottom before the bind (`scrollY + height >= child.height - 8dp`) and otherwise preserves the offset.

Layout additions inside `terminal_surface`, before `terminal_scroll`:
```xml
        <LinearLayout
            android:id="@+id/body_mode_row"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="top"
            android:background="@drawable/bg_pane_status_band"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingHorizontal="12dp"
            android:visibility="gone">
            <TextView
                android:id="@+id/body_mode_label"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:textColor="@color/collie_muted"
                android:textSize="12sp" />
            <com.google.android.material.button.MaterialButton
                android:id="@+id/body_mode_switch"
                style="@style/Widget.MaterialComponents.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:minHeight="36dp"
                android:textSize="12sp" />
        </LinearLayout>
        <com.lateapex.collie.ui.TranscriptBodyView
            android:id="@+id/transcript_body"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:visibility="gone" />
```
Strings:
```xml
    <string name="pane_transcript_load_older">Load older</string>
    <string name="pane_body_showing_transcript">Transcript</string>
    <string name="pane_body_showing_mirror_dialog">Terminal · agent is asking</string>
    <string name="pane_body_showing_mirror_raw">Terminal · raw</string>
    <string name="pane_body_showing_mirror_no_journal">Terminal</string>
    <string name="pane_body_switch_to_mirror">Show terminal</string>
    <string name="pane_body_switch_to_transcript">Show transcript</string>
```

PaneActivity state: `private var bodyOverride: PaneBody? = null` (the one-tap switch; cleared when the decision's reason changes, so a dialog appearing always shows the mirror and the user's override does not survive a pane switch, matching §5). In `render()` after the analysis block:
```kotlin
        val decided = PaneBodyDecision.decide(analyzedSemanticSurface, state.transcriptAvailable, displayPreferences.getBoolean(PREF_RAW, false), agentName)
        if (decided.reason != lastBodyReason) { bodyOverride = null; lastBodyReason = decided.reason }
        val body = bodyOverride ?: decided.body
        renderBodyModeRow(decided, body)
        binding.transcriptBody.isVisible = body == PaneBody.TRANSCRIPT
        binding.terminalScroll.isVisible = body == PaneBody.MIRROR
        binding.terminalStatusline.isVisible = body == PaneBody.MIRROR && statusRows.isNotEmpty()
        if (body == PaneBody.TRANSCRIPT) binding.transcriptBody.bind(agentName.orEmpty(), state.transcript, state.transcriptHasMore)
```
`renderBodyModeRow` shows the row only when the decision is not `TRANSCRIPT/NONE`-and-unoverridden for a journal agent, i.e. `row.isVisible = decided.reason != PaneBodyReason.NO_JOURNAL`; label from the reason; button text is the opposite body; click sets `bodyOverride` and re-renders. The `PENDING` reason shows the mirror with no row (the answer arrives on the next tick). The Raw switch in the Display drawer keeps working: it flips the preference and `render()` re-decides. `transcriptBody.onLoadOlder = viewModel::loadOlderTranscript` in `onCreate`. Find, Zen, tap-to-type and `pane_buffer_action` stay bound to the mirror (they are grid features); `openHistory` stays reachable from the buffer action and, when the transcript is showing, from the body mode row's label click (`bodyModeLabel.setOnClickListener { if (body == TRANSCRIPT) openHistory() }`) so A.7's "history from the header" is satisfied without a new header button.

- [ ] **Step 1: Failing tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
class TranscriptBodyViewTest {
    @Test
    fun bindAddsOneViewPerUuidAndReusesOnRebind() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TranscriptBodyView(context)
        val a = TranscriptEntry("a", "2026-09-10T00:00:00Z", "user", listOf(TranscriptPart("text", text = "hi")))
        val b = TranscriptEntry("b", "2026-09-10T00:00:01Z", "assistant", listOf(TranscriptPart("text", text = "yo")))
        view.bind("claude", listOf(a), hasMore = false)
        val first = view.turnViewForTest("a")
        view.bind("claude", listOf(a, b), hasMore = true)
        assertSame(first, view.turnViewForTest("a"))
        assertEquals(2, view.turnCountForTest())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.transcript_load_older).visibility)
    }
}
```
And in `PaneActivityTest`:
```kotlin
    @Test
    fun claudePaneShowsTranscriptWhenJournalIsAvailableAndNoDialog() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("body:pane", agent = "claude")).create().start().resume().get()
        activity.renderForTest(
            PaneReadResponse("body:pane", "⏺ done\n", truncated = false, revision = 0),
            transcript = listOf(TranscriptEntry("a", "2026-09-10T00:00:00Z", "assistant", listOf(TranscriptPart("text", text = "done")))),
            transcriptAvailable = true,
        )
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.transcript_body).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.terminal_scroll).visibility)
    }

    @Test
    fun dialogSwitchesTheWholeBodyToTheMirror() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("body:dialog", agent = "claude")).create().start().resume().get()
        val fixture = File("../../web/src/fixtures/panes/claude--select-ask-no-question-mark.txt").readText()
        activity.renderForTest(PaneReadResponse("body:dialog", fixture, truncated = false, revision = 0), transcript = emptyList(), transcriptAvailable = true)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.transcript_body).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.terminal_scroll).visibility)
        assertEquals(activity.getString(R.string.pane_body_showing_mirror_dialog), activity.findViewById<TextView>(R.id.body_mode_label).text.toString())
    }
```
Fixture path: `terminal/PromptBindingTest.kt:154` reads `File("../../web/src/fixtures/panes/$name")`; use the same relative path.

- [ ] **Step 2: Run** ONE `TranscriptBodyViewTest`, ONE `PaneActivityTest`. Expected: FAIL, unresolved.

- [ ] **Step 3: Implement** per Interfaces. `renderForTest` gains optional `transcript`/`transcriptAvailable` parameters that copy into the state it feeds `render`. `turnViewForTest`/`turnCountForTest` are `@VisibleForTesting internal`.

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/TranscriptBodyView.kt android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt android/app/src/main/res/layout/activity_pane.xml android/app/src/main/res/values/pane_strings.xml android/app/src/test/java/com/lateapex/collie/ui/TranscriptBodyViewTest.kt android/app/src/test/java/com/lateapex/collie/ui/PaneActivityTest.kt CHANGELOG.md
git commit -m "feat(android): show the journal transcript as the agent pane body"
```
CHANGELOG line: `- Android: an agent pane with a journal shows its transcript as the body and drops to the mirror only for a dialog, Raw, or no journal.`

### Task 9: Notices into the composer band, pane title not repeated, user-turn card (A.3, B.2, §4 item 4)

**Files:**
- Modify: `android/app/src/main/res/layout/activity_pane.xml:337-345` (`error_text` moves out of `terminal_surface` into `composer_chrome` above `pane_status`), `:176` (`show_tabs_button`)
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt` `renderStatus` (2059), `renderPaneNavigation` (2304, label at ~2323), `renderTerminalBlocks` (user-turn block)
- Modify: `android/app/src/main/res/drawable/bg_pane_notice.xml` (flat band, no elevation), new `android/app/src/main/res/drawable/bg_pane_user_turn.xml`
- Modify: `android/app/src/main/res/values/pane_strings.xml` (`pane_tabs_collapsed_count` plural)
- Test: `PaneActivityTest.kt`, `PaneLayoutTest.kt`

**Interfaces:** `error_text` keeps its id (every existing test and `renderStatus` reference it) and moves into `composer_chrome` as the first child, `layout_width=match_parent`, no `layout_gravity`, `elevation=0dp`. `bg_pane_notice.xml` becomes a solid `@color/collie_chrome` rectangle with a 1dp top stroke of `@color/collie_border`. The collapsed tab-strip label becomes `resources.getQuantityString(R.plurals.pane_tabs_collapsed_count, tabs.size, tabs.size)` ("1 tab" / "N tabs") with a `ic_history_chevron_down` end drawable, never the pane title.

- [ ] **Step 1: Failing tests**

```kotlin
    @Test
    fun noticeLivesInTheComposerBandNotOverTheMirror() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("notice:pane")).create().get()
        val notice = activity.findViewById<View>(R.id.error_text)
        assertSame(activity.findViewById<View>(R.id.composer_chrome), notice.parent)
        assertEquals(0f, notice.elevation, 0f)
    }

    @Test
    fun collapsedTabStripShowsTheTabCountNotTheTitle() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("tabs:pane")).create().start().resume().get()
        activity.renderForTest(tabs = listOf(tab("t1", "alpha"), tab("t2", "beta")), tabsCollapsed = true)
        assertEquals("2 tabs", activity.findViewById<TextView>(R.id.show_tabs_button).text.toString())
    }
```
`tab(id, label)` builds a `TabSummary` with the file's existing helper (see `PaneTabStripActivityTest` for the constructor).

- [ ] **Step 2: Run** ONE `PaneActivityTest`. Expected: FAIL.

- [ ] **Step 3: Implement**

Layout: cut the `error_text` block from lines 337–357 and paste it as the first child of `composer_chrome` (line 480), removing `layout_gravity` and `elevation`. `bg_pane_notice.xml`:
```xml
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item><shape><solid android:color="@color/collie_chrome" /></shape></item>
    <item android:bottom="-1dp" android:left="-1dp" android:right="-1dp"><shape><stroke android:width="1dp" android:color="@color/collie_border" /></shape></item>
</layer-list>
```
`renderPaneNavigation` (~2323): replace the `workspaceLabel › tabLabel` text with the plural and `setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_history_chevron_down, 0)`. Plural:
```xml
    <plurals name="pane_tabs_collapsed_count">
        <item quantity="one">%d tab</item>
        <item quantity="other">%d tabs</item>
    </plurals>
```
`renderTerminalBlocks`: the block builder that emits a user turn (the `>` prompt echo block, kind `user` in `TerminalBlocks`) sets `background = ContextCompat.getDrawable(this, R.drawable.bg_pane_user_turn)` and padding 12dp/8dp; `bg_pane_user_turn.xml` is a 10dp-radius `@color/collie_chrome_raised` fill (add the colour to `colors.xml` night/day if absent as `#1A1A1A` / `#F1F1F1`, the same values `bg_history_user` uses; copy them from that drawable).

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/res/layout/activity_pane.xml android/app/src/main/res/drawable/bg_pane_notice.xml android/app/src/main/res/drawable/bg_pane_user_turn.xml android/app/src/main/res/values/pane_strings.xml android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt android/app/src/test/java/com/lateapex/collie/ui/PaneActivityTest.kt CHANGELOG.md
git commit -m "fix(android): pane notices sit in the composer band and the tab strip stops repeating the title"
```
CHANGELOG line: `- Android: pane confirmations render in the composer band, the collapsed tab strip shows a tab count, and user turns read as cards.`

### Task 10: Type mode labels and stop icon; attachment chip; staged chips scroll cue (B.2)

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt` `setDirectTyping` (2127), attach insertion (~625-640), `bindExpandedKeys` chips row (~782)
- Create: `android/app/src/main/res/drawable/ic_composer_stop.xml`, `android/app/src/main/res/drawable/ic_attachment.xml`
- Modify: `android/app/src/main/res/layout/activity_pane.xml` (`composer_media_status` becomes a `com.google.android.material.chip.Chip` id `composer_attachment_chip`; keep `composer_media_status` for errors)
- Modify: `android/app/src/main/res/values/pane_strings.xml`
- Test: `PaneActivityTest.kt`, `ComposerMediaTest.kt`

**Interfaces:**
- Direct typing: hint stays `pane_direct_hint`; the second label `pane_direct_armed` is deleted from layout use (string removed); `sendButton.setImageResource(R.drawable.ic_composer_stop)` while armed, restored to the send icon on disarm; contentDescription stays `pane_direct_stop`.
- Attachment: the upload path is no longer written into `replyInput`. `PaneActivity` keeps `private var pendingAttachment: String?` (the server path the bridge returned); the chip shows the file's basename with a close icon; `sendReply` prepends the path plus a newline to the outgoing text when `pendingAttachment != null`, then clears it. `composer_media_status` shows only errors.
- Staged chips: the `HorizontalScrollView` around `keyQueueChips` gets `android:fadingEdgeLength="24dp" android:requiresFadingEdge="horizontal"`; that is the cue.

- [ ] **Step 1: Failing tests**

```kotlin
    @Test
    fun directTypingSwapsSendForStopAndShowsOneLabel() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("direct:pane")).create().start().resume().get()
        activity.setDirectTypingForTest(true)
        assertNull(activity.findViewById<View?>(R.id.pane_direct_armed_label))
        assertEquals(activity.getString(R.string.pane_direct_stop), activity.findViewById<View>(R.id.send_button).contentDescription)
        assertEquals(R.drawable.ic_composer_stop, Shadows.shadowOf(activity.findViewById<ImageView>(R.id.send_button).drawable).createdFromResId)
    }

    @Test
    fun attachmentIsAChipNotAPathInTheDraft() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("attach:pane")).create().start().resume().get()
        activity.acceptUploadForTest("/home/x/.local/state/collie/uploads/wM_p1.png")
        assertEquals("", activity.findViewById<EditText>(R.id.reply_input).text.toString())
        val chip = activity.findViewById<com.google.android.material.chip.Chip>(R.id.composer_attachment_chip)
        assertEquals(View.VISIBLE, chip.visibility)
        assertEquals("wM_p1.png", chip.text.toString())
        assertEquals("/home/x/.local/state/collie/uploads/wM_p1.png\nhello", activity.outgoingReplyForTest("hello"))
    }

    @Test
    fun stagedChipsRowFadesAtTheEdge() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("chips:pane")).create().get()
        val scroller = activity.findViewById<View>(R.id.pane_key_queue_scroll) as HorizontalScrollView
        assertTrue(scroller.isHorizontalFadingEdgeEnabled)
    }
```
`send_button` is an `AppCompatImageButton` (`activity_pane.xml:857`), so `setImageResource` and `shadowOf(drawable)` are correct as written. `pane_key_queue_scroll` is a new id on the existing HorizontalScrollView (ids live in `pane_key_ids.xml`, lane A adds it there or in `visual_ids.xml`; `visual_ids.xml` is unowned, so add it to `pane_key_ids.xml`).

- [ ] **Step 2: Run** ONE `PaneActivityTest`. Expected: FAIL.

- [ ] **Step 3: Implement**

`ic_composer_stop.xml`: 24dp vector, a 14×14 rounded square path `M7,7h10v10H7z` fill `?attr/colorOnSurface`. `ic_attachment.xml`: the Material "attach_file" path. `setDirectTyping(on)`: after setting the hint, `sendButton.setImageResource(if (on) R.drawable.ic_composer_stop else <the resource named at send_button's android:src in activity_pane.xml>)`; remove the `pane_direct_armed` TextView update and delete the string. Attach insertion (~625-640): replace `binding.replyInput.setText(insertion.text)` with `pendingAttachment = insertion.text; renderAttachmentChip()`; `renderAttachmentChip` sets `chip.text = File(path).name`, `chip.isVisible = true`, `chip.setOnCloseIconClickListener { pendingAttachment = null; renderAttachmentChip() }`. In the send path (`sendButton` click → `viewModel.sendReply(text, ...)`), compute `outgoingReply(text) = pendingAttachment?.let { "$it\n$text" } ?: text`, clear the attachment after `sendReply` is called. Expose `acceptUploadForTest`, `outgoingReplyForTest`, `setDirectTypingForTest` as `@VisibleForTesting internal`.

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt android/app/src/main/res/drawable/ic_composer_stop.xml android/app/src/main/res/drawable/ic_attachment.xml android/app/src/main/res/layout/activity_pane.xml android/app/src/main/res/values/pane_strings.xml android/app/src/main/res/values/pane_key_ids.xml android/app/src/test/java/com/lateapex/collie/ui/PaneActivityTest.kt android/app/src/test/java/com/lateapex/collie/ui/ComposerMediaTest.kt CHANGELOG.md
git commit -m "fix(android): type mode shows a stop icon, attachments are chips, staged keys fade at the edge"
```
CHANGELOG line: `- Android: direct typing shows one label and a stop icon, an attached image is a chip, and the staged-key row fades to show it scrolls.`

### Task 11: Compact Keys row and Display folded into the actions sheet (A.4, A.5, A.11, B.2)

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt` `bindExpandedKeys` (712), `showDrawer` (2085), `showPaneActions` (3015), `ComposerDrawer` enum (3650), the `display_prefs_container` wiring (~690-710)
- Modify: `android/app/src/main/res/layout/activity_pane.xml` (`key_row`, `display_prefs_container`, `mode_row`)
- Modify: `android/app/src/main/res/values/pane_strings.xml`, `pane_key_ids.xml`
- Test: `PaneActivityTest.kt`, `PaneKeyQueueTest.kt`, `PaneLayoutTest.kt`

**Interfaces:**
- Keys drawer becomes: staged-chip row (unchanged), one compact row `pane_keys_primary` holding `Esc Tab ↑ ↓ ← → Enter`, a second row `pane_keys_modifiers` with `Ctrl Alt Shift` as `MaterialButton` in `Widget.Collie.PaneKey` style with `checkable=true` and the checked state drawn by `app:strokeColor=@color/collie_accent` (so a sticky modifier is visibly a button, armed or not), and one `More` button (`pane_keys_more`, text `@string/pane_keys_more`) that opens a `CollieBottomSheetDialog` titled `@string/pane_keys_more_title` containing the existing Presets and Function keys groups (moved verbatim from the drawer into the sheet builder `showMoreKeysSheet()`). The `pane_keys_presets_toggle` and `pane_keys_functions_toggle` ids are removed with their disclosure rows. The 123 segment stays. With the drawer open at most 3 rows sit above the composer, so the terminal keeps rows (B.2 "presets leave zero rows").
- Display drawer is removed: `ComposerDrawer.DISPLAY` and `display_prefs_container` are deleted, `composer_settings_button` is deleted from `mode_row`. `showPaneActions` gains two `SwitchMaterial` rows, `Wrap lines` (`pane_action_wrap`) and `Raw terminal` (`pane_action_raw`), bound to `PREF_WRAP`/`PREF_RAW` with the same listeners `wrap_lines_switch` and `raw_terminal_switch` had. The text-size stepper is dropped from the pane (Settings has "Text size", string `settings_terminal_font_title`; the pane reads the same preference). Tap-to-type becomes always on: delete `tap_to_type_switch` and the `PREF_TAP_TO_TYPE` read; the mirror tap handler runs unconditionally.
- Sheets vs drawers (A.11): after this task the only in-composer drawers are Keys and Quick; both get the same header row the sheets have (title + ×), built by one helper `drawerHeader(title: String, onClose: () -> Unit): View` used for both, so one vocabulary remains.

- [ ] **Step 1: Failing tests**

```kotlin
    @Test
    fun keysDrawerHasOneCompactRowAndAMoreButton() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys:pane")).create().start().resume().get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        assertNull(activity.findViewById<View?>(R.id.pane_keys_presets_toggle))
        assertNull(activity.findViewById<View?>(R.id.pane_keys_functions_toggle))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_keys_more).visibility)
        val primary = activity.findViewById<ViewGroup>(R.id.pane_keys_primary)
        assertEquals(listOf("Esc", "Tab", "↑", "↓", "←", "→", "Enter"), (0 until primary.childCount).map { (primary.getChildAt(it) as TextView).text.toString() })
    }

    @Test
    fun displayDrawerIsGoneAndActionsSheetHoldsWrapAndRaw() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("display:pane")).create().start().resume().get()
        assertNull(activity.findViewById<View?>(R.id.display_prefs_container))
        assertNull(activity.findViewById<View?>(R.id.composer_settings_button))
        activity.showPaneActionsForTest()
        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        assertNotNull(sheet.findViewById<View>(R.id.pane_action_wrap))
        assertNotNull(sheet.findViewById<View>(R.id.pane_action_raw))
    }
```
`PaneKeyQueueTest` cases that open presets through `pane_keys_presets_toggle` are rewritten to call `activity.showMoreKeysSheetForTest()` and find the preset buttons in `ShadowDialog.getLatestDialog()`.

- [ ] **Step 2: Run** ONE `PaneActivityTest`, ONE `PaneKeyQueueTest`. Expected: FAIL.

- [ ] **Step 3: Implement** per Interfaces. Strings:
```xml
    <string name="pane_keys_more">More</string>
    <string name="pane_keys_more_title">Presets and function keys</string>
    <string name="pane_action_wrap">Wrap lines</string>
    <string name="pane_action_raw">Raw terminal</string>
```
Remove strings only the deleted views used (`pane_mode_settings`, the tap-to-type label, font smaller/larger labels) after `grep -rn` shows no other reference. Ids `pane_keys_more`, `pane_action_wrap`, `pane_action_raw` go in `pane_key_ids.xml`.

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt android/app/src/main/res/layout/activity_pane.xml android/app/src/main/res/values/pane_strings.xml android/app/src/main/res/values/pane_key_ids.xml android/app/src/test/java/com/lateapex/collie/ui/PaneActivityTest.kt android/app/src/test/java/com/lateapex/collie/ui/PaneKeyQueueTest.kt android/app/src/test/java/com/lateapex/collie/ui/PaneLayoutTest.kt CHANGELOG.md
git commit -m "feat(android): compact Keys row with a More sheet; Display folds into pane actions"
```
CHANGELOG line: `- Android: the Keys drawer is one compact row plus a More sheet, and Wrap/Raw live in the pane actions sheet; the Display drawer is gone.`

### Task 12: Rename sheet, close confirm, one status vocabulary (B.3, A.3/B.6)

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt` `showPaneActions` (3015), `showRenameCurrentPane` (~3379), `renderIdentity`/`paneStatus` (413, 449), `statusColour` (3636)
- Modify: `android/app/src/main/res/values/pane_strings.xml`
- Test: `PaneActivityTest.kt`, `PaneHeaderPresentationTest.kt`

**Interfaces:**
- Rename: `showRenameCurrentPane` opens its own `CollieBottomSheetDialog(this, getString(R.string.pane_rename_title))` (the string exists at `pane_strings.xml:30`) after dismissing the actions sheet; the field is prefilled with `currentPaneSummary()?.paneLabel?.takeIf { it.isNotBlank() } ?: dashboardPaneText(summary).primary` so an auto-titled pane still gets its shown name; Save with an empty field is the existing "clear label" path.
- Close: the two-tap confirm rebuilds the sheet content as a single red-filled button `pane_close_again` under the message `@string/pane_close_confirm_message` ("This closes the pane for everyone on this Herdr.") and the sheet title becomes `@string/pane_close_title` ("Close pane"); the other rows are removed for the confirm.
- Status vocabulary: the header dot (`renderIdentity`) and the `pane_status` label share one source. `AgentPresentation.kt` is lane C's, so lane A adds `ui/PaneStatusVocabulary.kt` with `fun label(resources, agent, status): String` and `fun colour(context, status): Int`, and `PaneActivity` uses it for the dot tint, `pane_status` text, and `statusColour`. The four surfaces (dot, label, dashboard ring, space chip dot) then all resolve through the same colour resource names `collie_working`, `collie_done`, `collie_blocked`, `collie_idle` (`colors.xml:24-27`); lane C's Task 17 points the ring and chip at the same names. Labels: `Working`, `Ready`, `Needs input`, `Idle`, `Shell`, all sentence case, replacing `WORKING/BLOCKED/DONE/SHELL`.

- [ ] **Step 1: Failing tests**

```kotlin
    @Test
    fun renameSheetIsTitledAndPrefilled() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("rename:pane")).create().start().resume().get()
        activity.renderForTest(panes = listOf(paneSummary("rename:pane", paneLabel = null, sessionName = "Codex session log 01a0")))
        activity.showRenameForTest()
        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        assertEquals("Rename pane", sheet.findViewById<TextView>(R.id.collie_sheet_title).text.toString())
        assertEquals("Codex session log 01a0", sheet.findViewById<EditText>(R.id.pane_rename_input).text.toString())
    }

    @Test
    fun statusLabelUsesOneVocabulary() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("status:v", agent = "claude")).create().start().resume().get()
        activity.renderForTest(panes = listOf(paneSummary("status:v", status = "blocked")))
        assertEquals("Needs input", activity.findViewById<TextView>(R.id.pane_status).text.toString())
    }
```
Read the sheet title with `sheet.findViewById<TextView>(R.id.collie_sheet_title).text` (`CollieBottomSheetDialog.kt:33`); do not edit that file.

- [ ] **Step 2: Run** ONE `PaneActivityTest`. Expected: FAIL.

- [ ] **Step 3: Implement** per Interfaces. `PaneStatusVocabulary`:
```kotlin
object PaneStatusVocabulary {
    fun label(resources: Resources, agent: String?, status: String?): String = when {
        agent.equals("shell", ignoreCase = true) -> resources.getString(R.string.pane_status_shell)
        status == "working" -> resources.getString(R.string.pane_status_working)
        status == "blocked" -> resources.getString(R.string.pane_status_needs_input)
        status == "done" -> resources.getString(R.string.pane_status_ready)
        else -> resources.getString(R.string.pane_status_idle)
    }
    @ColorInt fun colour(context: Context, status: String?): Int = ContextCompat.getColor(context, when (status) {
        "working" -> R.color.collie_working
        "blocked" -> R.color.collie_blocked
        "done" -> R.color.collie_done
        else -> R.color.collie_idle
    })
}
```
Strings `pane_status_shell`="Shell", `pane_status_working`="Working", `pane_status_needs_input`="Needs input", `pane_status_ready`="Ready", `pane_status_idle`="Idle", `pane_close_title`="Close pane", `pane_close_confirm_message`="This closes the pane for everyone on this Herdr." `AgentStatus` (`network/WireModels.kt:70`) serialises as `idle`, `working`, `blocked`, `done`, `unknown`; `status` here is that lower-case wire value.

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt android/app/src/main/java/com/lateapex/collie/ui/PaneStatusVocabulary.kt android/app/src/main/res/values/pane_strings.xml android/app/src/test/java/com/lateapex/collie/ui/PaneActivityTest.kt CHANGELOG.md
git commit -m "fix(android): rename sheet is titled and prefilled, close confirm is its own sheet, one status vocabulary"
```
CHANGELOG line: `- Android: Rename opens a titled, prefilled sheet, Close confirms in its own sheet, and the pane status label uses one sentence-case vocabulary.`

---

## Lane B — web mirror

### Task 13: Soft-wrap reflow in the web Claude adapter

**Files:**
- Create: `web/src/lib/harness/claude/reflow.ts`, `web/src/lib/harness/claude/reflow.test.ts`
- Modify: `web/src/lib/harness/claude/index.ts:103-115` (`stripChrome(lines)` call sites become `reflowSoftWraps(stripChrome(lines))`)
- Test: `web/src/lib/harness/claude/chrome.test.ts` (existing counts unchanged: reflow runs after `stripChrome`, and the `stripped` column measures `stripChrome` only)

**Interfaces:**
- Produces: `export function reflowSoftWraps(lines: StyledLine[], gridWidth = maxWidth(lines)): StyledLine[]` and `export function maxWidth(lines: StyledLine[]): number`. A joined line concatenates the segment arrays: `[...a.segments, { ...a.segments.at(-1), text: " " }, ...trimLeading(b)]`. `StyledLine` is `{ segments: AnsiSegment[]; noWrap?: true }` (`web/src/lib/blocks.ts:56`); `AnsiSegment` (`web/src/lib/ansi.ts:11`) is `{ text, fg?, bg?, bold?, dim?, … }`, so the style fields sit on the segment itself: build the joiner as `{ ...last, text: " " }`. A joined line keeps `noWrap` unset.
- Same structural exclusions as Task 1 (tables `│┃|`, rules, fences, bullets `•-*☐☒⎿⏺`, `N.`), same join rule (previous line's visible length equals the grid width, next line indented by at least two spaces and non-blank).

- [ ] **Step 1: Failing test**

```ts
import { describe, expect, it } from "vitest";
import { reflowSoftWraps, maxWidth } from "./reflow";
import type { StyledLine } from "@/lib/blocks";

const line = (text: string): StyledLine => ({ segments: [{ text }] });
const text = (l: StyledLine) => l.segments.map((s) => s.text).join("");

describe("reflowSoftWraps", () => {
  it("joins a full-width row with its indented continuation", () => {
    const first = "⏺ " + "x".repeat(38);
    const out = reflowSoftWraps([line(first), line("  continues"), line("")], 40);
    expect(out.map(text)).toEqual([`${first} continues`, ""]);
  });
  it("never joins tables, rules, fences or bullets", () => {
    const rows = ["│" + "a".repeat(38) + "│", "│ b │", "─".repeat(40), "  c", "x".repeat(40), "  • bullet"].map(line);
    expect(reflowSoftWraps(rows, 40).map(text)).toEqual(rows.map(text));
  });
  it("keeps the first row's style on the joined text", () => {
    const a: StyledLine = { segments: [{ text: "y".repeat(40), bold: true }] };
    const out = reflowSoftWraps([a, line("  tail")], 40);
    expect(out[0]!.segments[0]!.bold).toBe(true);
  });
  it("maxWidth is the longest visible row", () => {
    expect(maxWidth([line("ab"), line("twelve chars")])).toBe(12);
  });
});
```
The assertion is that the first segment's `bold` survives the join.

- [ ] **Step 2: Run** `cd web && bun run test -- --reporter=dot src/lib/harness/claude/reflow.test.ts | tail -5`. Expected: FAIL, cannot find module `./reflow`.

- [ ] **Step 3: Implement**

```ts
import type { StyledLine } from "@/lib/blocks";
import { lineText } from "./chrome";

const structural = /^\s*(?:[│┃|┌└├┬┴┼─━═]|```|[•\-*☐☒⎿⏺]\s|\d+\.\s)/;

export function maxWidth(lines: StyledLine[]): number {
  return lines.reduce((w, l) => Math.max(w, lineText(l).trimEnd().length), 0);
}

function canJoin(current: string, next: string, width: number): boolean {
  const trimmed = current.trimEnd();
  if (trimmed.length < width || next.trim() === "") return false;
  if (structural.test(trimmed) || structural.test(next)) return false;
  return next.length - next.trimStart().length >= 2;
}

function trimLeading(line: StyledLine): StyledLine["segments"] {
  const segments = line.segments.map((s) => ({ ...s }));
  for (const s of segments) {
    const t = s.text.trimStart();
    if (t.length > 0) { s.text = t; break; }
    s.text = "";
  }
  return segments.filter((s) => s.text.length > 0);
}

export function reflowSoftWraps(lines: StyledLine[], gridWidth = maxWidth(lines)): StyledLine[] {
  if (gridWidth <= 0 || lines.length < 2) return lines;
  const out: StyledLine[] = [];
  let i = 0;
  while (i < lines.length) {
    let current = lines[i]!;
    let currentText = lineText(current);
    let j = i + 1;
    while (j < lines.length && canJoin(currentText, lineText(lines[j]!), gridWidth)) {
      const last = current.segments.at(-1);
      current = { ...current, segments: [...current.segments, { ...(last ?? { text: "" }), text: " " }, ...trimLeading(lines[j]!)] };
      currentText = lineText(current);
      j++;
    }
    out.push(current);
    i = j;
  }
  return out;
}
```
`lineText` must be exported from `chrome.ts` if it is not already. In `index.ts` lines 103 and 115 wrap: `reflowSoftWraps(trimTrailingBlank(stripChrome(lines)))` and `{ kind: "raw", lines: reflowSoftWraps(stripChrome(lines)) }`.

- [ ] **Step 4: Run** WEB, WEBTYPES, LINT. Expected: all pass; the `chrome.test.ts` table is untouched because `stripped` counts `stripChrome` output. If `index.test.ts`/`table-run.test.ts` counts change, the change is a table row inside a fixture being joined: fix the regex so the row stays structural, never the fixture.

- [ ] **Step 5: Commit**

```bash
git add web/src/lib/harness/claude/reflow.ts web/src/lib/harness/claude/reflow.test.ts web/src/lib/harness/claude/index.ts web/src/lib/harness/claude/chrome.ts CHANGELOG.md
git commit -m "feat(web): reflow Claude's soft wraps before the mirror wraps once"
```
CHANGELOG line: `- Web: the Claude mirror joins the grid's soft wraps so a paragraph wraps once on a phone.`

### Task 14: Reflow fixture from the S25 capture

**Files:**
- Create: `web/src/fixtures/panes/claude--soft-wrapped-paragraph.txt`
- Modify: `web/src/lib/harness/claude/reflow.test.ts`

The fixture is the Herdr read behind `android/acceptance/2026-09-10-claude-pane-wrap.png` (the capture §1 of the proposal cites). Take it from the phone-visible pane via the bridge: `curl -s -H "Authorization: Bearer $(bin/collie devices token S25U-native 2>/dev/null || true)" https://collie.example-tailnet.ts.net/api/pane/<id>` is not available without the credential, so use the Herdr socket read the bridge uses: `herdr pane read <id> --lines 80` on workstation, saving the `text` field. If the pane no longer exists, reproduce: open a Claude pane, send "Explain in two paragraphs how Herdr's pane read works", wait for the answer, then read.

- [ ] **Step 1: Failing test** (append)

```ts
import { readFileSync } from "node:fs";
import { parseAnsi } from "@/lib/ansi";
it("reflows the S25 capture to fewer rows without touching its tables", () => {
  const raw = readFileSync(new URL("../../../fixtures/panes/claude--soft-wrapped-paragraph.txt", import.meta.url), "utf8");
  const lines = parseAnsi(raw);
  const out = reflowSoftWraps(lines);
  expect(out.length).toBeLessThan(lines.length);
  expect(out.filter((l) => lineText(l).startsWith("│")).length).toBe(lines.filter((l) => lineText(l).startsWith("│")).length);
});
```
Use the project's actual ANSI entry (`grep -n "export function" web/src/lib/ansi.ts`) for `parseAnsi`.

- [ ] **Step 2: Run** the reflow test file. Expected: FAIL, ENOENT on the fixture.

- [ ] **Step 3: Save the fixture** as described, then confirm `isRelevantFixture` in `chrome.test.ts` accepts the `claude--` prefix (it does for `claude--select-ask-no-question-mark.txt`).

- [ ] **Step 4: Run** WEB (`table-run.test.ts` and `chrome.test.ts` iterate every fixture; both must stay green). Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add web/src/fixtures/panes/claude--soft-wrapped-paragraph.txt web/src/lib/harness/claude/reflow.test.ts CHANGELOG.md
git commit -m "test(web): pin soft-wrap reflow against the S25 capture"
```
CHANGELOG line: `- Web: a phone-width Claude capture pins the soft-wrap reflow.`

---

## Lane C — dashboard, space, switcher

### Task 15: Space header collapses to one strip that includes and selects the current space (A.2, B.1)

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/SpaceActivity.kt:173-200` (`renderSpaceStrip`), `:200-250` (`renderTabStrip`), overview card builder
- Modify: `android/app/src/main/res/layout/activity_space.xml:98-130` (`spaces_strip`, `space_chips`, `tabs_strip`, overview)
- Test: `android/app/src/test/java/com/lateapex/collie/ui/SpaceActivityTest.kt`

**Interfaces:**
- The `spaces_strip` row is removed. The header shows a back arrow and the current space's name (existing `pane_header` pattern). Sibling switching moves to the switcher, which already lists spaces (A.2). The `+` (new space) moves to the tab strip's end after the `+ tab` button, keeping `SpaceActionModel.canUse(..., "createSpace")`.
- `renderTabStrip` marks the current tab with `active=true` and, after `addView`, posts `binding.tabsStrip.post { binding.tabsStrip.smoothScrollTo(chip.left - 24.dp, 0) }` for the active chip so it is on screen (B.1's rule applied to the strip that remains). The strip's `HorizontalScrollView` gets `requiresFadingEdge="horizontal"` and `fadingEdgeLength="24dp"` as the scroll cue.
- The overview card ("StormLens · 3 tabs · 3 panes") is removed; the header subtitle carries `N tabs · M panes`.
- Pane cards show the tab name inside the card's metadata line and the last non-blank mirror line (`PaneSummary.preview` if the wire has it; else `terminalTitle`) instead of "shell" three times.

- [ ] **Step 1: Failing tests**

```kotlin
    @Test
    fun spaceHeaderHasNoSiblingStripAndScrollsTheActiveTabIntoView() {
        val activity = launchSpace(workspaces = 14, tabs = 8, currentTab = 7)
        assertNull(activity.findViewById<View?>(R.id.spaces_strip))
        val strip = activity.findViewById<HorizontalScrollView>(R.id.tabs_strip)
        val chips = activity.findViewById<ViewGroup>(R.id.tab_chips)
        val active = chips.getChildAt(7)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(strip.scrollX >= active.left - 200)
        assertTrue(strip.isHorizontalFadingEdgeEnabled)
    }

    @Test
    fun overviewCardIsGoneAndSubtitleCarriesCounts() {
        val activity = launchSpace(workspaces = 2, tabs = 3, currentTab = 0)
        assertNull(activity.findViewById<View?>(R.id.space_overview_card))
        assertEquals("3 tabs · 3 panes", activity.findViewById<TextView>(R.id.space_subtitle).text.toString())
    }
```
`launchSpace` is the file's existing fake-content helper (it builds `SpaceContent`; extend it with the three parameters). Read `activity_space.xml` for the overview card id and the subtitle id and use those names.

- [ ] **Step 2: Run** ONE `SpaceActivityTest`. Expected: FAIL.

- [ ] **Step 3: Implement** per Interfaces. `spaceChip` and `spaceBackChip` become unused: delete them and the `space_accessibility` string only if `grep -rn` finds no other user.

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/SpaceActivity.kt android/app/src/main/res/layout/activity_space.xml android/app/src/main/res/values/dashboard_space_parity.xml android/app/src/test/java/com/lateapex/collie/ui/SpaceActivityTest.kt CHANGELOG.md
git commit -m "fix(android): Space header is one strip with the current tab in view"
```
CHANGELOG line: `- Android: the Space screen drops the sibling strip and overview card, and scrolls the current tab into view.`

### Task 16: Dashboard row titles lead with space and tab, middle truncation (A.1)

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/AgentPresentation.kt:52` (`dashboardPaneText`)
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneAdapter.kt:218-227` (`paneTitle`/`paneMetadata`), item layout's title `TextView` gets `android:ellipsize="middle"`
- Test: `android/app/src/test/java/com/lateapex/collie/ui/DashboardModelTest.kt` (or wherever `dashboardPaneText` is tested; `grep -rln dashboardPaneText android/app/src/test`)

**Interfaces:** `dashboardPaneText(summary): DashboardPaneText(primary, secondary)` changes its rule: `primary = paneLabel` when the operator named the pane; otherwise `primary = "<space> › <tab>"` and `secondary = sessionName ?: terminalTitle` (the agent's auto-title, demoted). Same helper serves the switcher (Task 17) so both agree.

- [ ] **Step 1: Failing test**

```kotlin
    @Test
    fun autoTitledPaneLeadsWithSpaceAndTab() {
        val text = dashboardPaneText(paneSummary(paneLabel = null, sessionName = "Codex session log 01a089a8", workspaceLabel = "StormLens", tabLabel = "api"))
        assertEquals("StormLens › api", text.primary)
        assertEquals("Codex session log 01a089a8", text.secondary)
    }

    @Test
    fun operatorLabelStaysPrimary() {
        val text = dashboardPaneText(paneSummary(paneLabel = "billing fix", sessionName = "x", workspaceLabel = "StormLens", tabLabel = "api"))
        assertEquals("billing fix", text.primary)
    }
```

- [ ] **Step 2: Run** ONE for that test class. Expected: FAIL.

- [ ] **Step 3: Implement** the rule; set `ellipsize="middle"` on the title view in the pane item layout (`grep -n 'pane_title' android/app/src/main/res/layout/item_*.xml`).

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/AgentPresentation.kt android/app/src/main/java/com/lateapex/collie/ui/PaneAdapter.kt android/app/src/main/res/layout android/app/src/test/java/com/lateapex/collie/ui CHANGELOG.md
git commit -m "fix(android): dashboard rows lead with space and tab and truncate in the middle"
```
CHANGELOG line: `- Android: dashboard and switcher rows lead with space › tab, demote the agent's auto-title, and truncate in the middle.`

### Task 17: Switcher current-pane marker; one status colour set; section chevrons (A.6, B.5, A.1)

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneAdapter.kt:426` (`PaneSectionHolder.bind`), `:634` (`SpacesHolder`), pane row holder (status ring tint)
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneSwitcher*.kt` (row builder; `grep -n 'fun row\|current' PaneSwitcher*.kt`)
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/SpaceActivity.kt` `tabChip` (281) dot tint
- Test: `PaneSwitcherModelTest.kt`, `DashboardShellActivityTest.kt`

**Interfaces:**
- Section toggles use `ic_history_chevron_down` (open) / `ic_history_chevron_right` (closed) via `setImageResource`, rotation 0 always; `ic_collie_back` is no longer used for disclosure. Applies to `PaneSectionHolder` and `SpacesHolder`.
- Switcher: the row for the pane whose address equals the switcher's `current` gets `isActivated = true`, a 3dp start bar in `@color/collie_accent`, and contentDescription suffixed `@string/switcher_current` ("current"). Working and Recent headings are both plain section headers (drop the Recent pill).
- Status colours: dashboard ring and Space chip dot tint from `R.color.collie_working`, `collie_done`, `collie_blocked`, `collie_idle` (`colors.xml:24-27`, the same names lane A's Task 12 uses). Any holder that tints from another colour today (`grep -n 'statusColor\|collie_working\|collie_blocked' PaneAdapter.kt SpaceActivity.kt`) switches to these four.

- [ ] **Step 1: Failing tests**

```kotlin
    @Test
    fun switcherMarksTheCurrentPane() {
        val rows = PaneSwitcherModel.rows(panes = listOf(pane("a"), pane("b")), current = address("b"))
        assertTrue(rows.single { it.address == address("b") }.current)
        assertFalse(rows.single { it.address == address("a") }.current)
    }

    @Test
    fun sectionToggleIsAChevronThatDoesNotRotate() {
        val activity = launchDashboard(recentOpen = false)
        val toggle = activity.findViewById<ImageView>(R.id.section_toggle)
        assertEquals(0f, toggle.rotation, 0f)
        assertEquals(R.drawable.ic_history_chevron_right, Shadows.shadowOf(toggle.drawable).createdFromResId)
    }
```
Use the real switcher model class name (`grep -n '^object\|^class' PaneSwitcherModel*.kt`) and the real toggle id.

- [ ] **Step 2: Run** ONE `PaneSwitcherModelTest`, ONE `DashboardShellActivityTest`. Expected: FAIL.

- [ ] **Step 3: Implement** per Interfaces.

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui android/app/src/main/res/values android/app/src/test/java/com/lateapex/collie/ui CHANGELOG.md
git commit -m "fix(android): switcher marks the current pane, sections use chevrons, one status palette"
```
CHANGELOG line: `- Android: the switcher marks the current pane, section toggles are chevrons, and every status dot and ring shares one palette.`

### Task 18: Spaces list rows say what is inside (A.1)

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneAdapter.kt` `SpacesHolder` row builder (~634-700) and the space row item layout
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/DashboardModel*.kt` (space row model gains counts)
- Test: `DashboardModelTest.kt`

**Interfaces:** `SpaceRow(label, age, paneCount, working: Int, blocked: Int, ready: Int)`; the grey pill is replaced by up to three small dots-with-count (`● 2 working · ● 1 needs input`) coloured from the shared status palette; a space with nothing active shows only `N panes`.

- [ ] **Step 1: Failing test**

```kotlin
    @Test
    fun spaceRowCountsStatuses() {
        val row = DashboardModel.spaceRow(workspace("w"), agents = listOf(agent("w", "working"), agent("w", "working"), agent("w", "blocked")))
        assertEquals(2, row.working); assertEquals(1, row.blocked); assertEquals(0, row.ready)
    }
```

- [ ] **Step 2: Run** ONE `DashboardModelTest`. Expected: FAIL.

- [ ] **Step 3: Implement** counts in the model; render in the holder with `TextView`s tinted via the palette; string `space_row_status` = `"%1$s · %2$s"`, `space_row_working` plural `"%d working"`, `space_row_blocked` plural `"%d needs input"`.

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui android/app/src/main/res android/app/src/test/java/com/lateapex/collie/ui/DashboardModelTest.kt CHANGELOG.md
git commit -m "feat(android): Spaces rows show working and needs-input counts"
```
CHANGELOG line: `- Android: each Spaces row shows how many panes are working or need input.`

### Task 19: Worktree creation is one sheet (B.3)

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/MainActivity.kt:927` (`chooseWorktreeRepo`), `+8` (`showWorktreeOptions`), `+32` (`showCreateWorktree`)
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: `DashboardShellActivityTest.kt` (or `NewSpaceHostModelTest.kt` if the sheet model lives there)

**Interfaces:** `showCreateWorktree(repos: List<WorktreeRepo>)` is the only sheet: title `worktree_create`; a repository selector (a `MaterialButtonToggleGroup` when `repos.size > 1`, a plain caption `worktree_repository_caption` "In %1$s" when exactly one); the branch field; a caption `worktree_base_caption` "New branch from %1$s" filled from the repo's default branch (`WorktreeRepo.defaultBranch`, read it from the wire model; if absent show the repo path only); and Create. `chooseWorktreeRepo` calls `showCreateWorktree` directly; `showWorktreeOptions` is deleted with `worktree_choose_repository` and `worktree_create_new_branch` if no other reference remains.

- [ ] **Step 1: Failing test**

```kotlin
    @Test
    fun worktreeFlowIsOneSheet() {
        val activity = launchDashboardWithWorktreeRepos(listOf(repo("collie-app", defaultBranch = "main")))
        activity.startWorktreeFlowForTest()
        val sheet = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
        assertNotNull(sheet.findViewById<EditText>(R.id.worktree_branch_input))
        assertEquals("New branch from main", sheet.findViewById<TextView>(R.id.worktree_base_caption).text.toString())
        assertEquals(1, ShadowDialog.getShownDialogs().size)
    }
```

- [ ] **Step 2: Run** the test class. Expected: FAIL.

- [ ] **Step 3: Implement** per Interfaces; ids in `dashboard_shell.xml` values (`worktree_branch_input`, `worktree_base_caption`).

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/MainActivity.kt android/app/src/main/res/values android/app/src/test/java/com/lateapex/collie/ui CHANGELOG.md
git commit -m "fix(android): create a worktree from one sheet that names repo and base"
```
CHANGELOG line: `- Android: worktree creation is one sheet showing the repository and base branch.`

---

## Lane D — settings, updates

### Task 20: Pairing card first, empty notifications card dropped, Zen row unconditional (A.8, A.9)

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/SettingsServerControls.kt:126-150` (`bind`), `:273` (`pushCard`), `:358` (`renderDevices`), `:408` (`pairForm`)
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/SettingsLocalPreferences.kt` (zen card removed; Zen row in the pane menu is lane A's `showPaneActions`, which after Task 11 must render the row unconditionally: lane A deletes the `nativePreferences.zenAvailable` guard at PaneActivity 3038 as part of Task 11; lane D deletes the preference and card)
- Modify: `android/app/src/main/res/layout/activity_settings.xml` (card order: `settings_pairing_card` placeholder above `settings_theme_card`)
- Test: `SettingsActivityTest.kt`

**Interfaces:**
- `bind(parent)` order becomes: pairing card (only while `repository.connection.value?.isPaired == false` (`domain/Connection.kt:23`), built by `pairForm()` with id `settings_parity_pairing_card`), then notify, snooze, navigation (Updates), devices, connection. `pushCard()` is deleted with `settings_background_unavailable` and `settings_push_state`. `renderDevices` no longer appends the pairing form.
- `NativePreferences.zenAvailable` is removed with its card strings (`settings_parity_zen_card`, `settings_zen_*`).

- [ ] **Step 1: Failing tests**

```kotlin
    @Test
    fun unpairedSettingsLeadsWithThePairingCard() {
        val activity = launchSettings(paired = false)
        val controls = activity.findViewById<LinearLayout>(R.id.settings_server_controls)
        assertEquals(R.id.settings_parity_pairing_card, controls.getChildAt(0).id)
        assertNull(activity.findViewById<View?>(R.id.settings_parity_push_card))
    }

    @Test
    fun pairedSettingsHasNoPairingCardAndNoZenCard() {
        val activity = launchSettings(paired = true)
        assertNull(activity.findViewById<View?>(R.id.settings_parity_pairing_card))
        assertNull(activity.findViewById<View?>(R.id.settings_parity_zen_card))
    }
```
`launchSettings(paired)` is the file's existing helper that seeds a `Connection` into the store; extend it with the flag if needed.

- [ ] **Step 2: Run** ONE `SettingsActivityTest`. Expected: FAIL.

- [ ] **Step 3: Implement** per Interfaces.

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/SettingsServerControls.kt android/app/src/main/java/com/lateapex/collie/ui/SettingsLocalPreferences.kt android/app/src/main/java/com/lateapex/collie/ui/NativePreferences.kt android/app/src/main/res/layout/activity_settings.xml android/app/src/main/res/values android/app/src/test/java/com/lateapex/collie/ui CHANGELOG.md
git commit -m "fix(android): pairing card leads Settings when unpaired; empty cards removed"
```
CHANGELOG line: `- Android: Settings leads with the pairing card when unpaired, drops the empty notifications card, and the Zen switch is gone (the row is always in the pane menu).`

### Task 21: Disconnect as a red button, Unpair label, pairing input parity (A.8, B.3, B.4)

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/SettingsActivity.kt:151-175` (`confirmDisconnect`), the `binding.disconnectButton` style in `activity_settings.xml`
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/SettingsServerControls.kt:408` (`pairForm`), `:481` (`deviceRow`)
- Modify: `android/app/src/main/res/values/settings_strings.xml`
- Test: `SettingsActivityTest.kt`

**Interfaces:**
- `disconnectButton` in the layout is a `MaterialButton` with `app:backgroundTint="@color/collie_destructive"` and white text; the sheet's confirm button is the same style (`style="@style/Widget.Collie.DestructiveButton"`, add the style to `settings_native_parity.xml` if no such style exists: filled, `backgroundTint` destructive, `textColor` `@color/collie_on_destructive` = white).
- `deviceRow` for the current device: button text `settings_unpair` (exists), description unchanged.
- `pairForm` uses `TextInputLayout` + `TextInputEditText` with `style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"` and the floating label `settings_pair_code`, matching the setup screen's fields; the cached `pairFormView` and refocus logic stay.

- [ ] **Step 1: Failing tests**

```kotlin
    @Test
    fun disconnectIsARedFilledButton() {
        val activity = launchSettings(paired = true)
        val button = activity.findViewById<MaterialButton>(R.id.disconnect_button)
        assertEquals(ContextCompat.getColor(activity, R.color.collie_destructive), button.backgroundTintList!!.defaultColor)
    }

    @Test
    fun ownDeviceRowSaysUnpair() {
        val activity = launchSettings(paired = true, devices = listOf(device("S25U-native", current = true)))
        val row = activity.findViewById<View>(R.id.settings_device_current_action) as TextView
        assertEquals(activity.getString(R.string.settings_unpair), row.text.toString())
    }

    @Test
    fun pairingFieldIsOutlined() {
        val activity = launchSettings(paired = false)
        assertNotNull(activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.settings_pair_input_layout))
    }
```
Add the ids `settings_device_current_action`, `settings_pair_input_layout` to `settings_ids.xml`.

- [ ] **Step 2: Run** ONE `SettingsActivityTest`. Expected: FAIL.

- [ ] **Step 3: Implement** per Interfaces.

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/SettingsActivity.kt android/app/src/main/java/com/lateapex/collie/ui/SettingsServerControls.kt android/app/src/main/res/layout/activity_settings.xml android/app/src/main/res/values android/app/src/test/java/com/lateapex/collie/ui/SettingsActivityTest.kt CHANGELOG.md
git commit -m "fix(android): disconnect is a red button, own device says Unpair, pairing field matches setup"
```
CHANGELOG line: `- Android: Disconnect is a red filled button, this phone's row says Unpair, and the pairing field uses the setup screen's outlined style.`

### Task 22: Updates card copy, remind button, fix lines (A.10, B.4)

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/UpdatesActivity.kt:139` (`updateCard`), `:319` (includes line), `:385-475` (`renderPreflight`, `preflightRow`, `renderActions`), `:188` (dismiss button)
- Modify: `android/app/src/main/res/values/update_native_parity.xml`
- Test: `UpdatesActivityParityTest.kt`

**Interfaces:**
- The "includes" line becomes `resources.getQuantityString(R.plurals.updates_native_behind, n, n)` ("one release behind" / "%d releases behind") where `n = newerVersions.size`; the version list is shown only inside the Details fold.
- The disabled Update button gets a one-line reason directly beneath it: `updates_native_blocked_reason` = "Blocked by %d preflight checks" (plural), from the red count; the three red rows stay in Details.
- "Remind me next digest" (`updates_native_dismiss`) becomes an outlined `MaterialButton` (`Widget.MaterialComponents.Button.OutlinedButton`) in `renderActions`.
- `preflightRow` no longer renders `check.remedy` as code; it shows `updates_native_fix_on_host` = "Fix on the host: %1$s" with the remedy's first line as plain text, so the phone stops presenting a shell command it cannot run.
- "Details · 3 red ^" becomes a `MaterialButton` text button with a chevron end icon (`ic_history_chevron_down`/`up`).

- [ ] **Step 1: Failing tests**

```kotlin
    @Test
    fun cardSaysReleasesBehindNotAVersionList() {
        val activity = launchUpdates(running = "1.5.1", latest = "1.8.0", newer = listOf("1.5.2","1.5.3","1.5.4","1.5.5","1.5.6","1.6.0","1.7.0","1.8.0"))
        val summary = activity.findViewById<TextView>(R.id.updates_native_summary).text.toString()
        assertTrue(summary.contains("8 releases behind"))
        assertFalse(summary.contains("1.5.2"))
    }

    @Test
    fun remindIsAButtonAndBlockedReasonIsOneLine() {
        val activity = launchUpdates(preflightRed = 3)
        assertTrue(activity.findViewById<View>(R.id.updates_snooze_button) is MaterialButton)
        assertEquals("Blocked by 3 preflight checks", activity.findViewById<TextView>(R.id.updates_native_blocked_reason).text.toString())
    }
```
`launchUpdates` is the parity test's existing helper; extend its parameters.

- [ ] **Step 2: Run** ONE `UpdatesActivityParityTest`. Expected: FAIL.

- [ ] **Step 3: Implement** per Interfaces. Plurals/strings in `update_native_parity.xml`:
```xml
    <plurals name="updates_native_behind">
        <item quantity="one">one release behind</item>
        <item quantity="other">%d releases behind</item>
    </plurals>
    <plurals name="updates_native_blocked_reason">
        <item quantity="one">Blocked by %d preflight check</item>
        <item quantity="other">Blocked by %d preflight checks</item>
    </plurals>
    <string name="updates_native_fix_on_host">Fix on the host: %1$s</string>
```
Delete the duplicate `updates_remind_digest` in `settings_strings.xml` if nothing else references it.

- [ ] **Step 4: Run** GATE. Expected: exit=0.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/lateapex/collie/ui/UpdatesActivity.kt android/app/src/main/res/values android/app/src/test/java/com/lateapex/collie/ui/UpdatesActivityParityTest.kt CHANGELOG.md
git commit -m "fix(android): Updates says releases behind, Remind is a button, blocked reason is one line"
```
CHANGELOG line: `- Android: Updates counts releases behind, shows the blocked reason in one line, makes Remind a button, and stops printing host shell remedies as code.`

---

## Integration

### Task 23: Merge lanes, gate, install, walk the acceptance list (§6)

**Files:**
- Modify: `android/acceptance/` (new captures only), `ANDROID_CLAUDE_PANE_UX_PROPOSAL.md` §6 checkboxes
- Everything else read-only.

- [ ] **Step 1: Merge** lane branches into `feat/android-twa` in order A, B, C, D. On a `CHANGELOG.md` conflict keep every line under `## [Unreleased]`, lane A's first.

- [ ] **Step 2: Run** GATE, WEB, WEBTYPES, LINT, and `bun test bridge/prompt-binding.test.ts`. Expected: every command exit 0. Paste each command's final line into the report.

- [ ] **Step 3: Install** with INSTALL. Expected: `Success`.

- [ ] **Step 4: Walk §6** on the S25 Ultra (wireless ADB `192.0.2.10:37363`; `screencap` each state to `android/acceptance/2026-09-10-ux-<state>.png`). Each of these is a PASS only when the named state is observed in the capture:
  - Claude pane opens on the transcript; the body mode row is hidden; user turn renders as a card.
  - Send `AskUserQuestion`-shaped prompt (teammate agent `collie-49` can be asked to trigger one); body switches to the mirror with row "Terminal · agent is asking"; answering switches back within one poll.
  - Tap "Show terminal" on the transcript; mirror shows with statusline row pinned; a long paragraph wraps once (no ragged indent); `⎿` renders as a straight tree marker.
  - Shell pane: mirror, no mode row, tab strip label shows "N tabs".
  - "Reply sent." appears in the composer band, not over the mirror.
  - Type mode: hint only, stop icon on the send button.
  - Attach image: chip with basename; send delivers path + text (verify in `~/.local/state/collie/audit.log`).
  - Keys: one compact row, Ctrl shows as a button, More opens the presets sheet, terminal rows remain visible.
  - Pane actions: Wrap and Raw switches present; Rename sheet titled and prefilled; Close confirm in its own red sheet.
  - Space: no sibling strip, current tab in view, no overview card.
  - Dashboard: rows lead with space › tab; chevrons; Spaces rows show counts; switcher marks current pane.
  - Settings unpaired (revoke `S25U-native` with `bin/collie devices revoke`, then re-pair with `bin/collie pair`): pairing card first, outlined field; paired: no pairing card, red Disconnect, Unpair label.
  - Updates: "N releases behind", Remind button, blocked reason line.
- [ ] **Step 5: Commit** the captures and the ticked §6 list.

```bash
git add android/acceptance ANDROID_CLAUDE_PANE_UX_PROPOSAL.md
git commit -m "docs(android): record S25 acceptance for the pane UX plan"
```
No CHANGELOG line (docs and captures only).

---

## Self-review against the spec

- §4 item 1 → Tasks 5–8. Item 2 → Tasks 7–8. Item 3 reflow → Tasks 1–2 (native), 13–14 (web); status row → Task 3. Item 4 → Task 9.
- §5 invariants: composer, semantic panel, direct typing, Keys queue rules untouched by any task; `AgentSemanticParser` is read, never edited.
- §6 acceptance → Task 23.
- A.1 → Tasks 16, 17, 18 (build stamp already removed from lists in commit history; Settings keeps `settings_build_stamp`). A.2 → 15. A.3 → 9, 12. A.4 → 11. A.5 → 11. A.6 → 16, 17. A.7 → 8 (label click opens history). A.8 → 20, 21. A.9 → 20, 21. A.10 → 22. A.11 → 11 (drawer header helper), 12 (palette), 4 (one mono face; the two-title-font item is closed by the drawer header helper using `collie_ui` for every code-built title). B.1 → 15. B.2 → 3, 4, 9, 10, 11. B.3 → 12, 19, 21. B.4 → 21, 22. B.5 → 17 (landscape: Task 11's shorter Keys drawer and Task 9's flat notices are the changes that recover rows; no further landscape-specific layout is added). B.6 → 12, 17. B.7 → 15, 9, 10, 12, 4.
- Type consistency: `PaneUiState.transcript/transcriptAvailable/transcriptReason/transcriptHasMore` (Task 6) are what Task 7's `decide` and Task 8's `render` read; `HistoryTurnRenderer.turnView` (Task 5) is what `TranscriptBodyView.bind` (Task 8) calls; `ClaudeChromeFilter.split` (Task 3) replaces the `filter` call Task 2 introduced in `renderTerminalContent`; status colour resource names `collie_working`, `collie_done`, `collie_blocked`, `collie_idle` are the existing `colors.xml` entries that Tasks 12 and 17 both reference.
