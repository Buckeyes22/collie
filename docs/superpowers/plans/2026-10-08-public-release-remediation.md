# Public Release Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every finding of the 2026-10-08 publication review (3 blockers, 13 should-fix, 4 nice-to-have) so the source tree, its history and the first signed APK can be published under this fork.

**Architecture:** Notice and provenance fixes land first, because the in-app notices screen and the release tooling package them. Code-level fixes (generic agent tiles, the hardcoded home path, signing provenance, action pins) follow. Repository-settings changes come after the workflow files they protect. Launch wording lands last, in the release commit.

**Tech Stack:** Kotlin/Android views (Robolectric tests), Bun/TypeScript scripts (`bun test`), Bash release tooling, GitHub Actions, `gh` CLI for repository settings.

**Spec:** the publication review pasted into the 2026-10-08 session. Each task names the finding it closes in bold. Evidence lives in `android/validation/hardening/history-assets-review.md`.

## Global Constraints

- Public Android builds keep an empty `DEFAULT_ORIGIN`. Never ship a maintainer's server, hostname, LAN address or home path in code, docs or captures.
- Android is a native client: no WebView, Custom Tab, TWA or JS bridge (ADR 0036). The notices screen is a native `TextView`.
- Every user-visible Android string goes in `res/values/*.xml`, in English only (ADR 0037).
- Android changes add one line under `ANDROID_CHANGELOG.md` → `## Unreleased`. Changes under `scripts/` and `web/public/` add one line at the end of `CHANGELOG.md` → `## [Unreleased]`, with no hash. No version files are bumped except in Task 18.
- No `oxlint-disable` comments. Run `bun run lint` on every TypeScript change.
- Run both typechecks after any TS change: `bun run typecheck` and `cd web && bun run typecheck`.
- Run the Android gate once per batch, not per edit: `cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug 2>&1 | tail -40`.
- Never delete an existing feature, test or endpoint without listing it in **Decisions** and getting Chris's yes.
- Commit trailer on every commit: the `Co-Authored-By` and `Claude-Session` lines from the session.

## Decisions (Chris answers these before the dependent task runs)

| # | Question | Recommended default | Blocks |
| --- | --- | --- | --- |
| D1 | Do the existing acceptance screenshots stay in public history? | **Yes.** The fork is already public, so its history is already mirrored. Remove the real-session captures from the tip, replace them with synthetic captures, and record the decision. A `git filter-repo` rewrite cannot recall copies that forks, clones and caches already hold. It also breaks every fork and tag. | Task 11, Task 12 |
| D2 | Which refs are published? | `main` plus the `android-v*` tags. Delete the four stale remote branches (`audit/content-redaction`, `commands/operator-rows`, `fix/110-partial-arrival`, `fix/122-public-url`) and `feat/android-twa`, after Chris says yes to that list. | Task 12 |
| D3 | Does the PWA's `web/src/components/agent-icon-data.ts` swap to the same generic tiles as Android? | **No.** Leave upstream's PWA data and record its provenance in `ACKNOWLEDGMENTS.md`. A swap would delete these tests in `web/src/components/agent-icon.test.tsx`: "paints omp's mark with its own gradient, referenced by a resolvable id" and the duplicate-gradient-id test at lines 41–70. | Task 3 Step 7 |
| D4 | Contribution policy | DCO sign-off (`git commit -s`), checked at review rather than enforced by a bot. Adopt Contributor Covenant 2.1 with a report route to chris@lateapexllc.com. | Task 15 |

## Review Focus

1. **A dependency added to `gradle.lockfile` without a license mapping.** The notices generator must fail the CI check, not emit a row with an empty license. Pinned by Task 5 Step 1 (`unmapped module fails`).
2. **A signing run against an APK rebuilt after the manifest was written, or with a dirty tree.** `android-release.sh` must refuse both. Pinned by Task 7 Step 1 (`swapped APK` and `dirty tree` cases).
3. **A pane whose cwd sits under someone else's home that starts with the same prefix** (`/home/chris` vs `/home/christine`), or a server that reports no home at all. The header must show the raw path and never guess a home. Pinned by Task 4 Step 1.
4. **R8 stripping the new `dependency_notices` raw resource from the release APK.** `keep.xml` must list it, and the notices test reads it through `resources`. Pinned by Task 5 Step 6 and Task 6 Step 1.
5. **A workflow edited later with a fresh `@vN` tag.** The pin test must catch any non-SHA `uses:` in any workflow. Pinned by Task 8 Step 1.

---

## File ownership (for parallel lanes)

If lanes run this plan, each lane prompt starts from `~/.claude/templates/lane-prompt.md` with the exclusive list below. `ANDROID_CHANGELOG.md` and `CHANGELOG.md` are shared append-only files: lanes do not write them. The integrator appends each lane's changelog line at merge. Every other path belongs to exactly one task.

| Task | Exclusive files |
| --- | --- |
| 1 | `android/app/src/main/res/raw/third_party_notices.txt` (the Goose block only), `scripts/notices.test.ts` (create) |
| 2 | `web/public/licenses/` (create), the eight shadcn-derived files listed in Task 2, `ACKNOWLEDGMENTS.md` (the shadcn section) |
| 3 | `android/app/src/main/res/drawable/ic_agent_{claude,codex,pi,omp,antigravity}.xml`, `android/README.md` (the agent-marks paragraphs), `AgentIconsTest.kt` |
| 4 | `DisplayText.kt`, `DisplayTextTest.kt`, `PaneActivity.kt` (lines 425–440 and the new `applyHomeToHeader`) |
| 5 | `scripts/android-sbom.ts`, `scripts/android-dependency-notices.ts` (+test), `android/dependency-licenses.json`, `android/licenses/Apache-2.0.txt`, `res/raw/dependency_notices.txt`, `res/raw/keep.xml` |
| 6 | `LicensesActivity.kt`, `activity_licenses.xml`, `activity_settings.xml`, `SettingsActivity.kt`, `AndroidManifest.xml`, `settings_strings.xml`, `LicensesActivityTest.kt` |
| 7 | `scripts/android-build-manifest.sh`, `scripts/android-release.sh`, `scripts/android-release.test.sh`, `package.json` (`test` script) |
| 8 | `.github/workflows/*.yml` (`uses:` lines only), `scripts/workflow-pins.test.ts` |
| 9 | `.github/workflows/triage.yml` (comments only), `.github/scripts/README.md` or `docs/` triage section |
| 10 | the redaction file list in Task 10 |
| 11 | `android/acceptance/*.png`, `ANDROID_CLAUDE_PANE_UX_PROPOSAL.md` (capture references) |
| 12 | `android/validation/hardening/history-assets-review.md` |
| 13 | `UPSTREAM_README.md` |
| 14 | `README.md`, `android/RELEASING.md`, `SECURITY.md` (release-state sentences) |
| 15 | `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` (create) |
| 19 | `scripts/build-nerd-font.sh`, `web/public/fonts/LICENSE-nerd-symbols.txt` |
| 16–18 | GitHub settings and the release commit: integrator only |

Order by dependency: 1 → 2 → 3 → 4 → 5 → 6 (needs 1, 3, 5) → 7 → 8 → 9 → 10 → 11 → 12 (needs D1, D2, 10, 11) → 13 → 14 → 15 → 16 (needs 8) → 17 (needs 16) → 18 (needs everything). Task 19 touches nothing the others use and may run at any point before 18.

---

### Task 1: Restore Goose's copyright line (BLOCKER)

**Closes:** *third_party_notices.txt:258 — Goose's copyright replaced by template placeholders.*

The Goose block reproduces the Apache appendix verbatim, including its template line `Copyright [yyyy] [name of copyright owner]`. The pinned upstream LICENSE fills that line in. The APK therefore ships Goose's license without Goose's copyright holder.

**Files:**
- Modify: `android/app/src/main/res/raw/third_party_notices.txt:258`
- Create: `scripts/notices.test.ts` (later tasks extend it)

**Interfaces:**
- Produces: `scripts/notices.test.ts` with a `read(path)` helper and one `describe("notices")` block that Tasks 2, 4 and 5 add cases to.

- [ ] **Step 1: Confirm the pinned upstream text**

Run: `curl -fsSL https://raw.githubusercontent.com/block/goose/dce69009546ce5f20522d010fa3f1d57abbe2c3f/LICENSE | grep -n "Copyright"`
Expected: one line that reads `Copyright 2024 Block, Inc.` If the line differs, use the upstream line verbatim in Step 3 and the test.

- [ ] **Step 2: Write the failing test**

```ts
// scripts/notices.test.ts
import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dir, "..");
const read = (path: string) => readFileSync(resolve(root, path), "utf8");
const ANDROID_NOTICES = "android/app/src/main/res/raw/third_party_notices.txt";

describe("notices", () => {
  test("the Goose block carries Block's copyright, not the Apache template", () => {
    const text = read(ANDROID_NOTICES);
    expect(text).toContain("Copyright 2024 Block, Inc.");
    expect(text).not.toContain("[yyyy]");
    expect(text).not.toContain("[name of copyright owner]");
  });
});
```

- [ ] **Step 3: Run it to see it fail**

Run: `bun test scripts/notices.test.ts`
Expected: FAIL, because `Copyright 2024 Block, Inc.` is not found.

- [ ] **Step 4: Fix line 258**

Replace `   Copyright [yyyy] [name of copyright owner]` with `   Copyright 2024 Block, Inc.` Keep the three-space indent. Also add the same line to the Goose header block near line 63 (`Licensed under Apache License 2.0.`) as its own line above it, so the holder is visible without scrolling to the appendix.

- [ ] **Step 5: Run the test and the Android checker**

Run: `bun test scripts/notices.test.ts && bun run check:android`
Expected: `1 pass`, and the checker prints its success line.

- [ ] **Step 6: Commit**

Add `- Goose's notice names its copyright holder, Block, Inc.` to `ANDROID_CHANGELOG.md` → Unreleased.

```bash
git add scripts/notices.test.ts android/app/src/main/res/raw/third_party_notices.txt ANDROID_CHANGELOG.md CHANGELOG.md
git commit -m "fix(android): Goose's notice carries Block's copyright line"
```

`scripts/notices.test.ts` is under `scripts/`, so it also needs a `CHANGELOG.md` line: `- A notices test pins the packaged third-party copyright lines.`

---

### Task 2: Ship shadcn/ui and shadcn-chat notices with the PWA (BLOCKER)

**Closes:** *web/src/components/ui/button.tsx:6 — adapted shadcn components lack shadcn's MIT notice.*

`web/components.json` declares the shadcn `new-york` style. Two upstream projects fed `web/src/components/ui/`. shadcn/ui supplied `button`, `badge`, `card`, `sheet` and `switch`. shadcn-chat (jakobhoeg) supplied `chat/chat-input`, `chat/chat-message-list` and `hooks/use-auto-scroll`. The Vite build bundles all eight into `web/dist`, so the notice must ship in `web/public` (copied verbatim into `dist`), not only in source.

**Files:**
- Create: `web/public/licenses/shadcn-ui.txt`, `web/public/licenses/shadcn-chat.txt`
- Modify: `web/src/components/ui/{button,badge,card,sheet,switch}.tsx`, `web/src/components/ui/chat/{chat-input,chat-message-list}.tsx`, `web/src/hooks/use-auto-scroll.ts` (line 1: one comment line each)
- Modify: `ACKNOWLEDGMENTS.md` (new `## Web components` section)
- Test: `scripts/notices.test.ts`

- [ ] **Step 1: Confirm the covered set against upstream**

Run:
```bash
for c in button badge card sheet switch; do
  printf '%s ' "$c"; curl -fsSo /dev/null -w '%{http_code}\n' \
    "https://raw.githubusercontent.com/shadcn-ui/ui/main/apps/v4/registry/new-york-v4/ui/$c.tsx"
done
curl -fsSL https://raw.githubusercontent.com/jakobhoeg/shadcn-chat/master/LICENSE | head -3
```
Expected: `200` for all five, and the shadcn-chat LICENSE heading with its copyright line. A component whose registry file returns 404 is project-authored. Drop it from the list in Steps 2–4.

- [ ] **Step 2: Write the failing test** (append inside `describe("notices")`)

```ts
  const SHADCN_UI = ["button", "badge", "card", "sheet", "switch"].map(
    (c) => `web/src/components/ui/${c}.tsx`,
  );
  const SHADCN_CHAT = [
    "web/src/components/ui/chat/chat-input.tsx",
    "web/src/components/ui/chat/chat-message-list.tsx",
    "web/src/hooks/use-auto-scroll.ts",
  ];

  test("the PWA ships shadcn/ui's and shadcn-chat's MIT notices", () => {
    expect(read("web/public/licenses/shadcn-ui.txt")).toMatch(/Copyright \(c\) \d{4} shadcn/);
    expect(read("web/public/licenses/shadcn-chat.txt")).toContain("Permission is hereby granted");
  });

  test.each(SHADCN_UI)("%s names its shadcn/ui origin", (path) => {
    expect(read(path)).toContain("licenses/shadcn-ui.txt");
  });

  test.each(SHADCN_CHAT)("%s names its shadcn-chat origin", (path) => {
    expect(read(path)).toContain("licenses/shadcn-chat.txt");
  });
```

- [ ] **Step 3: Run it to see it fail**

Run: `bun test scripts/notices.test.ts`
Expected: FAIL with `ENOENT` on `web/public/licenses/shadcn-ui.txt`.

- [ ] **Step 4: Add the notices**

```bash
mkdir -p web/public/licenses
curl -fsSL https://raw.githubusercontent.com/shadcn-ui/ui/main/LICENSE.md -o web/public/licenses/shadcn-ui.txt
curl -fsSL https://raw.githubusercontent.com/jakobhoeg/shadcn-chat/master/LICENSE -o web/public/licenses/shadcn-chat.txt
```

Add one line at the top of each shadcn/ui file:
```ts
// Adapted from shadcn/ui (MIT): see web/public/licenses/shadcn-ui.txt.
```
Add one line at the top of each shadcn-chat file:
```ts
// Adapted from shadcn-chat (MIT): see web/public/licenses/shadcn-chat.txt.
```

Add to `ACKNOWLEDGMENTS.md` after the first paragraph:
```markdown
## Web components

The retained PWA adapts components from two MIT projects. Their notices ship in the built app
under `/licenses/`:

| Upstream | Files | Notice |
| --- | --- | --- |
| [shadcn/ui](https://github.com/shadcn-ui/ui) | `ui/button`, `ui/badge`, `ui/card`, `ui/sheet`, `ui/switch` | [shadcn-ui.txt](web/public/licenses/shadcn-ui.txt) |
| [shadcn-chat](https://github.com/jakobhoeg/shadcn-chat) | `ui/chat/chat-input`, `ui/chat/chat-message-list`, `hooks/use-auto-scroll` | [shadcn-chat.txt](web/public/licenses/shadcn-chat.txt) |
```

- [ ] **Step 5: Verify the notice reaches the bundle**

Run: `bun test scripts/notices.test.ts && bun run lint && bun run typecheck && (cd web && bun run typecheck) && bun run build >/dev/null && ls web/dist/licenses/`
Expected: all tests pass, lint and both typechecks are clean, and `shadcn-chat.txt  shadcn-ui.txt` is listed.

- [ ] **Step 6: Commit**

Add `- The PWA ships the shadcn/ui and shadcn-chat MIT notices under /licenses/.` to `CHANGELOG.md` → `[Unreleased]`.

```bash
git add web/public/licenses web/src/components/ui web/src/hooks/use-auto-scroll.ts ACKNOWLEDGMENTS.md scripts/notices.test.ts CHANGELOG.md
git commit -m "fix(web): ship shadcn/ui and shadcn-chat MIT notices"
```

---

### Task 3: Replace unprovenanced vendor marks with original tiles

**Closes:** *android/README.md:263 (Claude, pi, OMP provenance), ic_agent_codex.xml:8 (OpenAI mark composed into a custom tile), android/README.md:266 (Antigravity glyph of unestablished authorship).*

`AgentIcons.kt:12-16` maps those five agent families to drawables that carry vendor geometry. That geometry is either unprovenanced (Claude, pi, OMP), presented in a way OpenAI's guidelines forbid (a recolored mark on a custom tile), or of unknown origin (Antigravity). Kimi and Qwen already show the fix shape: `ic_agent_kimi.xml` is a locally authored letter tile with an XML comment that says so. Replacing these five with the same shape removes every open question at once. Copyright terms, trademark presentation and authorship all stop applying. Tile **colors** are neutral, because brand colors are part of trade dress.

**Files:**
- Modify: `android/app/src/main/res/drawable/ic_agent_{claude,codex,pi,omp,antigravity}.xml`
- Modify: `android/README.md:262-268`
- Test: `android/app/src/test/java/com/lateapex/collie/ui/AgentIconsTest.kt`

- [ ] **Step 1: Write the failing test** (add to `AgentIconsTest`)

```kotlin
@Test
fun formerVendorMarkTilesAreLocallyAuthored() {
    val names = listOf("claude", "codex", "pi", "omp", "antigravity")
    val res = java.io.File("src/main/res/drawable")
    names.forEach { name ->
        val xml = res.resolve("ic_agent_$name.xml").readText()
        assertTrue("$name tile must declare local authorship", xml.contains("Locally authored"))
    }
    // The OpenAI knot path that ic_agent_codex.xml carried must be gone.
    assertFalse(res.resolve("ic_agent_codex.xml").readText().contains("M22.2819,9.8211"))
}
```

Robolectric runs unit tests with the module directory (`android/app`) as the working directory, which is why the relative path works.

- [ ] **Step 2: Run it to see it fail**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests '*AgentIconsTest*' 2>&1 | tail -15`
Expected: FAIL on `claude tile must declare local authorship`.

- [ ] **Step 3: Write the five tiles**

All five share the frame of `ic_agent_kimi.xml`: a 16dp/24-viewport vector, a `#080A0F` square, a white hole-less glyph and one 4×4 accent square.

`ic_agent_claude.xml`: a block C.
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="16dp"
    android:height="16dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path android:fillColor="#080A0F" android:pathData="M0,0H24V24H0Z" />
    <!-- Locally authored C identification tile; this is not Anthropic's logo artwork. -->
    <path android:fillColor="#FFFFFF" android:pathData="M6,4H18V7H9V17H18V20H6Z" />
    <path android:fillColor="#E0A060" android:pathData="M17,3H21V7H17Z" />
</vector>
```

`ic_agent_codex.xml`: a terminal prompt, `>_`.
```xml
    <!-- Locally authored prompt identification tile; this is not OpenAI's logo artwork. -->
    <path android:fillColor="#FFFFFF" android:pathData="M4,6L10,12L4,18H7.6L13.6,12L7.6,6Z" />
    <path android:fillColor="#FFFFFF" android:pathData="M13,16H20V19H13Z" />
    <path android:fillColor="#9AA4B2" android:pathData="M17,3H21V7H17Z" />
```

`ic_agent_pi.xml`: a typographic π.
```xml
    <!-- Locally authored pi identification tile; this is not pi.dev's logo artwork. -->
    <path android:fillColor="#FFFFFF" android:pathData="M4,5H20V8H17V20H14V8H10V20H7V8H4Z" />
    <path android:fillColor="#4CC38A" android:pathData="M17,3H21V7H17Z" />
```

`ic_agent_omp.xml`: a block M.
```xml
    <!-- Locally authored M identification tile; this is not omp.sh's logo artwork. -->
    <path android:fillColor="#FFFFFF" android:pathData="M4,20V4H7.5L12,10.5L16.5,4H20V20H17V9.5L12,16.5L7,9.5V20Z" />
    <path android:fillColor="#B07CF0" android:pathData="M17,3H21V7H17Z" />
```

`ic_agent_antigravity.xml`: a counterless block A.
```xml
    <!-- Locally authored A identification tile; this is not Google's Antigravity product icon. -->
    <path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="M4,20L10,4H14L20,20H16.6L15.1,16H8.9L7.4,20ZM10,13H14L12,7.6Z" />
    <path android:fillColor="#5B9CF0" android:pathData="M17,3H21V7H17Z" />
```
`evenOdd` makes the inner `ZM…Z` subpath open the A's counter.

Each of the four non-Claude files keeps the same `<?xml>`, `<vector>` and background `<path>` lines as the Claude file above.

- [ ] **Step 4: Rewrite the README paragraph**

Replace `android/README.md:262-268` ("The native dashboard and pane header reuse the agent marks…") with:
```markdown
Claude, Codex, pi, OMP and Antigravity use locally authored letter or glyph tiles on a neutral
square, like Kimi and Qwen. They identify the agent a pane runs and redistribute no vendor
artwork, so they carry no third-party notice. The PWA's `web/src/components/agent-icon-data.ts`
keeps upstream Collie's own tile selection; see ACKNOWLEDGMENTS.md.
```

- [ ] **Step 5: Run the test, then render the tiles**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests '*AgentIconsTest*' 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

Then install the debug APK on the S25 Ultra, open the dashboard and look at each tile. Say why first if you use the Pixel instead. Check that each glyph is centered, legible at 16dp, and clear of its accent square.

- [ ] **Step 6: Record the decisions in `history-assets-review.md`**

In the APK-provenance table, change the Claude, Codex/OpenAI, pi, OMP and Antigravity rows from `REVIEW-NEEDED` to `FIX`. Each row's text becomes: "Replaced with a locally authored tile in `ic_agent_<name>.xml`; the APK no longer carries this vendor's artwork."

- [ ] **Step 7: Record the PWA disposition (per D3)**

Under the `## Web components` section from Task 2, add:
```markdown
The PWA's agent tiles (`web/src/components/agent-icon-data.ts`) are upstream Collie's selection,
with their recorded sources in that file's header. They identify the agent a pane runs and imply
no endorsement. The Android app does not package the Claude, Codex, pi, OMP or Antigravity marks.
```

- [ ] **Step 8: Commit**

Add `- Claude, Codex, pi, OMP and Antigravity panes show locally authored tiles.` to `ANDROID_CHANGELOG.md` → Unreleased.

```bash
git add android/app/src/main/res/drawable/ic_agent_*.xml android/README.md ACKNOWLEDGMENTS.md \
  android/app/src/test/java/com/lateapex/collie/ui/AgentIconsTest.kt \
  android/validation/hardening/history-assets-review.md ANDROID_CHANGELOG.md
git commit -m "fix(android): five agent tiles are locally authored, not vendor marks"
```

---

### Task 4: Remove the maintainer's home path from shipped code

**Closes:** *Operational addresses, hostnames and personal paths remain in source* (the instance in shipped Kotlin, found while grounding this plan).

`DisplayText.abbreviateHome(path, home = "/home/chris")` (`DisplayText.kt:4`) defaults to the maintainer's home. `PaneActivity.kt:434` calls it with that default, so every install shows `~` only for paths under `/home/chris`. The server already reports the real home: `repository.launchers(scope)` returns `LaunchersResponse.home`, which `PaneActivity.kt:2877` reads for the switcher.

**Files:**
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/DisplayText.kt:4`
- Modify: `android/app/src/main/java/com/lateapex/collie/ui/PaneActivity.kt:434`
- Test: `android/app/src/test/java/com/lateapex/collie/ui/DisplayTextTest.kt`, `scripts/notices.test.ts`

**Interfaces:**
- Produces: `DisplayText.abbreviateHome(path: String, home: String): String`, where `home` is now required, and a private `PaneActivity.applyHomeToHeader(cwd: String)`.

- [ ] **Step 1: Write the failing tests**

Replace `abbreviatesChrisHomeWithoutTouchingOtherPaths` in `DisplayTextTest.kt`. This is a rewrite of an existing test, so list it under Decisions if Chris must approve it; it keeps every assertion except the two that relied on the default.
```kotlin
@Test
fun abbreviatesOnlyTheReportedHome() {
    assertEquals("~", DisplayText.abbreviateHome("/home/operator", "/home/operator"))
    assertEquals("~/git/app", DisplayText.abbreviateHome("/home/operator/git/app", "/home/operator"))
    assertEquals("/mnt/work/app", DisplayText.abbreviateHome("/mnt/work/app", "/home/operator"))
    assertEquals("/home/operators/repo", DisplayText.abbreviateHome("/home/operators/repo", "/home/operator"))
    assertEquals("~/src/collie", DisplayText.abbreviateHome("/srv/operator/src/collie", "/srv/operator"))
    assertEquals("/srv/operators/repo", DisplayText.abbreviateHome("/srv/operators/repo", "/srv/operator"))
}

@Test
fun anUnknownHomeLeavesThePathAlone() {
    assertEquals("/home/operator/git/app", DisplayText.abbreviateHome("/home/operator/git/app", ""))
}
```

Add a guard to `scripts/notices.test.ts`:
```ts
  test("shipped Android sources carry no maintainer path, tailnet host or LAN address", () => {
    const { execSync } = require("node:child_process") as typeof import("node:child_process");
    const hits = execSync(
      "git grep -nE '/home/chris|\\.tail[0-9a-f]{6}\\.ts\\.net|192\\.168\\.[0-9]+\\.[0-9]+' -- android/app/src/main || true",
      { cwd: root, encoding: "utf8" },
    );
    expect(hits).toBe("");
  });
```

- [ ] **Step 2: Run them to see them fail**

Run: `bun test scripts/notices.test.ts; cd android && ./gradlew --no-daemon testDebugUnitTest --tests '*DisplayTextTest*' 2>&1 | tail -5`
Expected: the Bun guard FAILS and names `DisplayText.kt:4`. The Kotlin test is expected to pass already; it exists to pin the behaviour across the signature change.

- [ ] **Step 3: Make `home` required and feed the header the server's home**

`DisplayText.kt:4`:
```kotlin
    fun abbreviateHome(path: String, home: String): String = when {
```

`PaneActivity.kt:434`: render the raw path first, then abbreviate once the server answers.
```kotlin
        binding.paneCwd.text = cwd
        applyHomeToHeader(cwd)
```

Add, next to the switcher code that already calls `repository.launchers`:
```kotlin
    private fun applyHomeToHeader(cwd: String) {
        lifecycleScope.launch {
            val result = repository.launchers(address.scope)
            if (result is ApiResult.Success && !isFinishing) {
                binding.paneCwd.text = DisplayText.abbreviateHome(cwd, result.value.home)
            }
        }
    }
```

- [ ] **Step 4: Fix the remaining fixture paths**

Replace `/home/chris` with `/home/operator` and `chris@ed8` with `operator@host` in these test files: `DashboardModelTest.kt:224,739`, `PaneSwitcherModelTest.kt:26,52`, `SpaceModelTest.kt:126,140,142`, `terminal/ClaudeChromeFilterTest.kt:29`. In `ClaudeChromeFilterTest.kt:29`, also replace `/home/chris/git/prometheus` with `/home/operator/git/app`. Keep each assertion's expected value consistent with its new input.

- [ ] **Step 5: Run the gate**

Run: `bun test scripts/notices.test.ts && cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug 2>&1 | tail -20`
Expected: Bun passes, and Gradle prints `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Add `- The pane header shortens the server's reported home, not a hardcoded one.` to `ANDROID_CHANGELOG.md`.

```bash
git add android/app/src scripts/notices.test.ts ANDROID_CHANGELOG.md
git commit -m "fix(android): the pane header abbreviates the server's home, not the maintainer's"
```

---

### Task 5: Map every locked runtime dependency to its license and source

**Closes:** *ACKNOWLEDGMENTS.md:8 — no dependency-to-license/source mapping* (the inventory half; Task 6 adds the screen).

`scripts/android-sbom.ts:androidSbom` already parses `releaseRuntimeClasspath` out of `android/app/gradle.lockfile`. Today it locks 80 coordinates in 44 groups. Extract that parser, add a committed group→license map, and generate a packaged `res/raw/dependency_notices.txt`. CI fails when the lockfile gains a module the map does not cover.

**Files:**
- Modify: `scripts/android-sbom.ts` (export `releaseModules`)
- Create: `scripts/android-dependency-notices.ts`, `scripts/android-dependency-notices.test.ts`
- Create: `android/dependency-licenses.json`, `android/licenses/Apache-2.0.txt`, `android/app/src/main/res/raw/dependency_notices.txt` (generated)
- Modify: `android/app/src/main/res/raw/keep.xml`, `.github/workflows/ci.yml` and `.github/workflows/android-release.yml` (one step each), `scripts/android-release.sh:62` (copy the new file)

**Interfaces:**
- Produces: `releaseModules(lockfile: string): { group: string; name: string; version: string }[]` from `android-sbom.ts`, sorted by `group:name`.
- Produces: `dependencyNotices(lockfile: string, map: LicenseMap, apache: string): string`, where `LicenseMap = { rules: { prefix: string; license: string; source: string; extra?: string }[] }`. The longest matching `prefix` against `group:name` wins.

- [ ] **Step 1: Write the failing tests**

```ts
// scripts/android-dependency-notices.test.ts
import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { dependencyNotices } from "./android-dependency-notices";

const root = resolve(import.meta.dir, "..");
const map = { rules: [
  { prefix: "androidx.", license: "Apache-2.0", source: "https://android.googlesource.com/platform/frameworks/support" },
  { prefix: "com.squareup.okhttp3:", license: "Apache-2.0", source: "https://github.com/square/okhttp",
    extra: "OkHttp bundles the Public Suffix List (MPL-2.0), https://publicsuffix.org/." },
] };
const lock = (...c: string[]) => c.map((x) => `${x}=releaseRuntimeClasspath`).join("\n");

describe("dependencyNotices", () => {
  test("lists each module with its license and source, then the license text once", () => {
    const out = dependencyNotices(lock("androidx.core:core:1.0.0", "com.squareup.okhttp3:okhttp:4.12.0"), map, "APACHE TEXT");
    expect(out).toContain("androidx.core:core:1.0.0 — Apache-2.0 — https://android.googlesource.com/platform/frameworks/support");
    expect(out).toContain("Public Suffix List (MPL-2.0)");
    expect(out.match(/APACHE TEXT/g)?.length).toBe(1);
  });

  test("an unmapped module fails instead of emitting an empty license", () => {
    expect(() => dependencyNotices(lock("org.example:lib:1.0"), map, "x")).toThrow("org.example:lib has no license mapping");
  });

  test("the packaged file matches the current lockfile and map", () => {
    const expected = dependencyNotices(
      readFileSync(resolve(root, "android/app/gradle.lockfile"), "utf8"),
      JSON.parse(readFileSync(resolve(root, "android/dependency-licenses.json"), "utf8")),
      readFileSync(resolve(root, "android/licenses/Apache-2.0.txt"), "utf8"),
    );
    expect(readFileSync(resolve(root, "android/app/src/main/res/raw/dependency_notices.txt"), "utf8")).toBe(expected);
  });
});
```

- [ ] **Step 2: Run them to see them fail**

Run: `bun test scripts/android-dependency-notices.test.ts`
Expected: FAIL with `Cannot find module './android-dependency-notices'`.

- [ ] **Step 3: Extract `releaseModules` from `androidSbom`**

In `scripts/android-sbom.ts`, move the lockfile loop (lines 9–25, from `const modules = new Map` through the `modules.size === 0` throw) into:
```ts
export function releaseModules(lockfile: string) {
  // …the existing loop, unchanged…
  return [...modules.values()].sort((a, b) =>
    `${a.group}:${a.name}`.localeCompare(`${b.group}:${b.name}`));
}
```
`androidSbom` then calls `releaseModules(lockfile)` in place of the loop. Run `bun test scripts/android-sbom.test.ts`; it must stay at 4 pass.

- [ ] **Step 4: Write the generator**

```ts
// scripts/android-dependency-notices.ts
// Writes res/raw/dependency_notices.txt from the locked release classpath. `--check` diffs instead.
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { releaseModules } from "./android-sbom";

export interface LicenseMap { rules: { prefix: string; license: string; source: string; extra?: string }[] }

export function dependencyNotices(lockfile: string, map: LicenseMap, apache: string): string {
  const rows: string[] = [];
  const extras = new Set<string>();
  for (const m of releaseModules(lockfile)) {
    const key = `${m.group}:${m.name}`;
    const rule = map.rules
      .filter((r) => key.startsWith(r.prefix))
      .sort((a, b) => b.prefix.length - a.prefix.length)[0];
    if (!rule) throw new Error(`${key} has no license mapping`);
    rows.push(`${key}:${m.version} — ${rule.license} — ${rule.source}`);
    if (rule.extra) extras.add(rule.extra);
  }
  return [
    "RUNTIME DEPENDENCIES",
    "Every library in the release runtime classpath, from android/app/gradle.lockfile.",
    "",
    ...rows,
    "",
    ...[...extras],
    "",
    "APACHE LICENSE 2.0",
    apache.trimEnd(),
    "",
  ].join("\n");
}

if (import.meta.main) {
  const root = resolve(import.meta.dir, "..");
  const out = resolve(root, "android/app/src/main/res/raw/dependency_notices.txt");
  const text = dependencyNotices(
    readFileSync(resolve(root, "android/app/gradle.lockfile"), "utf8"),
    JSON.parse(readFileSync(resolve(root, "android/dependency-licenses.json"), "utf8")) as LicenseMap,
    readFileSync(resolve(root, "android/licenses/Apache-2.0.txt"), "utf8"),
  );
  if (process.argv.includes("--check")) {
    if (readFileSync(out, "utf8") !== text) {
      console.error("dependency_notices.txt is stale: run bun scripts/android-dependency-notices.ts");
      process.exit(1);
    }
  } else writeFileSync(out, text);
}
```

- [ ] **Step 5: Write the map from the locked groups**

`curl -fsSL https://www.apache.org/licenses/LICENSE-2.0.txt -o android/licenses/Apache-2.0.txt`

`android/dependency-licenses.json`, covering every group in `grep releaseRuntimeClasspath android/app/gradle.lockfile | cut -d: -f1 | sort -u`:
```json
{
  "rules": [
    { "prefix": "androidx.", "license": "Apache-2.0", "source": "https://android.googlesource.com/platform/frameworks/support" },
    { "prefix": "com.google.android.material:", "license": "Apache-2.0", "source": "https://github.com/material-components/material-components-android" },
    { "prefix": "com.google.errorprone:", "license": "Apache-2.0", "source": "https://github.com/google/error-prone" },
    { "prefix": "com.google.guava:", "license": "Apache-2.0", "source": "https://github.com/google/guava" },
    { "prefix": "com.squareup.okhttp3:", "license": "Apache-2.0", "source": "https://github.com/square/okhttp",
      "extra": "OkHttp bundles the Public Suffix List, Mozilla Public License 2.0, https://publicsuffix.org/; its notice ships inside the okhttp artifact." },
    { "prefix": "com.squareup.okio:", "license": "Apache-2.0", "source": "https://github.com/square/okio" },
    { "prefix": "org.jetbrains:annotations", "license": "Apache-2.0", "source": "https://github.com/JetBrains/java-annotations" },
    { "prefix": "org.jetbrains.kotlin:", "license": "Apache-2.0", "source": "https://github.com/JetBrains/kotlin" },
    { "prefix": "org.jetbrains.kotlinx:kotlinx-coroutines", "license": "Apache-2.0", "source": "https://github.com/Kotlin/kotlinx.coroutines" },
    { "prefix": "org.jetbrains.kotlinx:kotlinx-serialization", "license": "Apache-2.0", "source": "https://github.com/Kotlin/kotlinx.serialization" },
    { "prefix": "org.jspecify:", "license": "Apache-2.0", "source": "https://github.com/jspecify/jspecify" }
  ]
}
```
Run the generator. If it throws `… has no license mapping`, look up that module's POM `<licenses>` with `curl -fsSL https://repo1.maven.org/maven2/<group path>/<name>/<version>/<name>-<version>.pom | grep -A3 '<license>'` (Google artifacts: `https://dl.google.com/android/maven2/...`). Add a rule that cites the license the POM declares. Never guess a license.

- [ ] **Step 6: Package it and keep it through R8**

`keep.xml`: append `,@raw/dependency_notices` to `tools:keep`.
`scripts/android-release.sh:62`: after the `third_party_notices.txt` copy, add `cp "$repo_root/android/app/src/main/res/raw/dependency_notices.txt" "$stage/"`.
`ci.yml` and `android-release.yml`, inside the "Validate native …" step: add `bun scripts/android-dependency-notices.ts --check`.

- [ ] **Step 7: Run everything this touches**

Run: `bun scripts/android-dependency-notices.ts && bun test scripts/android-dependency-notices.test.ts scripts/android-sbom.test.ts && bun scripts/android-dependency-notices.ts --check && bun run lint && bun run typecheck`
Expected: 7 pass, the check exits 0, lint and typecheck are clean.

- [ ] **Step 8: Commit**

`CHANGELOG.md`: `- Android release tooling generates a dependency license inventory from the lockfile.`
`ANDROID_CHANGELOG.md`: `- The APK packages a license and source line for every runtime library.`

```bash
git add scripts/android-sbom.ts scripts/android-dependency-notices*.ts android/dependency-licenses.json \
  android/licenses android/app/src/main/res/raw/dependency_notices.txt android/app/src/main/res/raw/keep.xml \
  scripts/android-release.sh .github/workflows/ci.yml .github/workflows/android-release.yml CHANGELOG.md ANDROID_CHANGELOG.md
git commit -m "feat(android): package a mapped runtime dependency license inventory"
```

---

### Task 6: An in-app open-source licenses screen

**Closes:** *ACKNOWLEDGMENTS.md:8 — add an accessible app notices screen.*

The five raw notice resources survive R8, but nothing in the app shows them. A native `LicensesActivity` concatenates them into one selectable `TextView`. Settings opens it from a row below the build stamp (`activity_settings.xml:341`).

**Files:**
- Create: `android/app/src/main/java/com/lateapex/collie/ui/LicensesActivity.kt`, `android/app/src/main/res/layout/activity_licenses.xml`
- Modify: `activity_settings.xml` (button above `settings_build_stamp`), `SettingsActivity.kt:onCreate`, `AndroidManifest.xml`, `res/values/settings_strings.xml`
- Test: `android/app/src/test/java/com/lateapex/collie/ui/LicensesActivityTest.kt`

**Interfaces:**
- Consumes: `R.raw.license_collie`, `R.raw.third_party_notices`, `R.raw.dependency_notices` (Task 5), `R.raw.license_aldrich`, `R.raw.license_jetbrains_mono`.
- Produces: `LicensesActivity.NOTICE_RESOURCES: List<Int>`, and view ids `settings_licenses_button` and `licenses_text`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.lateapex.collie.ui

import android.content.Intent
import android.widget.TextView
import com.lateapex.collie.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LicensesActivityTest {
    @Test
    fun showsEveryPackagedNotice() {
        val activity = Robolectric.buildActivity(LicensesActivity::class.java).setup().get()
        val text = activity.findViewById<TextView>(R.id.licenses_text).text.toString()
        assertTrue(text.contains("MIT License"))
        assertTrue(text.contains("Copyright 2024 Block, Inc."))
        assertTrue(text.contains("com.squareup.okhttp3:okhttp"))
        assertTrue(text.contains("SIL OPEN FONT LICENSE"))
        assertTrue(activity.findViewById<TextView>(R.id.licenses_text).isTextSelectable)
    }

    @Test
    fun settingsOpensTheLicensesScreen() {
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        settings.findViewById<android.view.View>(R.id.settings_licenses_button).performClick()
        val next: Intent = shadowOf(settings).nextStartedActivity
        assertEquals(LicensesActivity::class.java.name, next.component?.className)
    }
}
```
If `SettingsActivity` needs the connection-store setup that `SettingsActivityTest`'s `@Before` performs, copy that setup into this class verbatim.

- [ ] **Step 2: Run them to see them fail**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests '*LicensesActivityTest*' 2>&1 | tail -10`
Expected: a compile failure, `Unresolved reference: LicensesActivity`.

- [ ] **Step 3: Implement the screen**

`settings_strings.xml`:
```xml
    <string name="settings_licenses">Open-source licenses</string>
    <string name="licenses_title">Open-source licenses</string>
```

`activity_licenses.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/licenses_scroll"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/collie_background">
    <TextView
        android:id="@+id/licenses_text"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="16dp"
        android:fontFamily="monospace"
        android:textColor="@color/collie_foreground"
        android:textIsSelectable="true"
        android:textSize="11sp" />
</ScrollView>
```
Use the background color resource that `activity_settings.xml`'s root uses if it is not named `collie_background`.

`LicensesActivity.kt`:
```kotlin
package com.lateapex.collie.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lateapex.collie.R

class LicensesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)
        title = getString(R.string.licenses_title)
        findViewById<TextView>(R.id.licenses_text).text = NOTICE_RESOURCES.joinToString("\n\n") { id ->
            resources.openRawResource(id).bufferedReader().use { it.readText() }.trimEnd()
        }
    }

    internal companion object {
        val NOTICE_RESOURCES = listOf(
            R.raw.license_collie,
            R.raw.third_party_notices,
            R.raw.dependency_notices,
            R.raw.license_aldrich,
            R.raw.license_jetbrains_mono,
        )
    }
}
```
Apply the same system-bar inset handling `HistoryActivity` uses (`SystemBarInsets.kt`) to `licenses_scroll`.

`activity_settings.xml`, immediately above the `settings_build_stamp` `TextView`:
```xml
            <com.google.android.material.button.MaterialButton
                android:id="@+id/settings_licenses_button"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="@dimen/collie_touch_target"
                android:layout_gravity="center_horizontal"
                android:layout_marginTop="16dp"
                android:text="@string/settings_licenses" />
```

`SettingsActivity.onCreate`, after the existing bindings:
```kotlin
        binding.settingsLicensesButton.setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
```

`AndroidManifest.xml`, beside the other activities:
```xml
        <activity android:name=".ui.LicensesActivity" android:exported="false" />
```
Use the same `android:name` form and attributes as the neighbouring `HistoryActivity` entry.

- [ ] **Step 4: Run the gate and the checker**

Run: `bun run check:android && cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug 2>&1 | tail -20`
Expected: the checker passes (the new activity is unexported) and Gradle prints `BUILD SUCCESSFUL`.

- [ ] **Step 5: Device check**

Install on the S25 Ultra, open Settings → Open-source licenses, and scroll to the end. Confirm the text is legible in both themes and that TalkBack reads the title.

- [ ] **Step 6: Commit**

`ANDROID_CHANGELOG.md`: `- Settings opens an Open-source licenses screen with every packaged notice.`

```bash
git add android/app/src ANDROID_CHANGELOG.md
git commit -m "feat(android): Settings shows the packaged open-source notices"
```

---

### Task 7: Signing proves which build inputs produced the APK

**Closes:** *scripts/android-release.sh:48 — local signing records HEAD and lockfile beside any unsigned APK without proving the relationship.*

Today `android-release.sh` signs whatever `app-release-unsigned.apk` is on disk. It then records `git rev-parse HEAD` at signing time (line 60), plus a `worktreeDirty` flag (line 72) that warns but never refuses. The fix splits the work. A build-time manifest records the commit, lockfile hash and unsigned-APK hash immediately after `assembleRelease`, on a clean tree. Signing then refuses a dirty tree, a missing manifest, or any mismatch between the manifest and what is on disk now.

**Files:**
- Create: `scripts/android-build-manifest.sh`
- Modify: `scripts/android-release.sh:18-20,60-75`
- Modify: `.github/workflows/android-release.yml` (build job: run the manifest script, upload it)
- Modify: `android/RELEASING.md` (local route: the manifest step)
- Create: `scripts/android-release.test.sh`; Modify: `package.json` `test` script

**Interfaces:**
- Produces: `android/app/build/outputs/apk/release/build-inputs.json`, `{"sourceCommit": "<40 hex>", "lockfileSha256": "<64 hex>", "unsignedApkSha256": "<64 hex>"}`.
- `release-metadata.json` drops `worktreeDirty` and gains `"buildInputs": <the manifest object>` plus `"buildInputsVerified": true`.

- [ ] **Step 1: Write the failing test**

```bash
#!/usr/bin/env bash
# scripts/android-release.test.sh — drives android-release.sh against a stub SDK in a scratch repo.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
fail() { echo "FAIL: $1" >&2; exit 1; }
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

setup() {
  rm -rf "$work/repo" "$work/out"; mkdir -p "$work/repo/scripts" "$work/repo/android/app/src/main/res/raw" \
    "$work/repo/android/app/build/outputs/apk/release" "$work/sdk/build-tools/36.0.0"
  cp "$here"/scripts/android-{release.sh,build-manifest.sh,sbom.ts} "$work/repo/scripts/"
  cp "$here/LICENSE" "$work/repo/"; cp "$here/android/app/gradle.lockfile" "$work/repo/android/app/"
  cp "$here"/android/app/src/main/res/raw/*.txt "$work/repo/android/app/src/main/res/raw/"
  printf 'build/\n' > "$work/repo/android/app/.gitignore"
  cat > "$work/sdk/build-tools/36.0.0/aapt" <<'EOF'
#!/usr/bin/env bash
echo "package: name='com.lateapex.collie' versionCode='2' versionName='1.5.1'"
EOF
  cat > "$work/sdk/build-tools/36.0.0/apksigner" <<'EOF'
#!/usr/bin/env bash
if [[ $1 == sign ]]; then while [[ $1 != --out ]]; do shift; done; cp "${@: -1}" "$2"; exit 0; fi
echo "Signer #1 certificate SHA-256 digest: $(printf 'a%.0s' {1..64})"
EOF
  chmod +x "$work/sdk/build-tools/36.0.0/"*
  git -C "$work/repo" init -q && git -C "$work/repo" add -A && git -C "$work/repo" -c user.email=t@t -c user.name=t commit -qm init
  echo apk > "$work/repo/android/app/build/outputs/apk/release/app-release-unsigned.apk"
  : > "$work/ks"
}
sign() {
  ANDROID_HOME="$work/sdk" COLLIE_ANDROID_KEYSTORE="$work/ks" COLLIE_ANDROID_KEY_ALIAS=a \
  COLLIE_ANDROID_STORE_PASSWORD=p COLLIE_ANDROID_KEY_PASSWORD=p \
  COLLIE_ANDROID_CERT_SHA256="$(printf 'a%.0s' {1..64})" COLLIE_ANDROID_RELEASE_DIR="$work/out" \
  bash "$work/repo/scripts/android-release.sh"
}

setup; sign 2>/dev/null && fail "signed without a build-inputs manifest"
setup; bash "$work/repo/scripts/android-build-manifest.sh"
echo swapped > "$work/repo/android/app/build/outputs/apk/release/app-release-unsigned.apk"
sign 2>/dev/null && fail "signed an APK rebuilt after the manifest"
setup; bash "$work/repo/scripts/android-build-manifest.sh"; echo x >> "$work/repo/LICENSE"
sign 2>/dev/null && fail "signed from a dirty tree"
setup; echo x >> "$work/repo/LICENSE"
bash "$work/repo/scripts/android-build-manifest.sh" 2>/dev/null && fail "wrote a manifest from a dirty tree"
setup; bash "$work/repo/scripts/android-build-manifest.sh"; sign >/dev/null
grep -q '"buildInputsVerified": true' "$work/out/release-metadata.json" || fail "metadata lacks verified build inputs"
grep -q worktreeDirty "$work/out/release-metadata.json" && fail "metadata still carries worktreeDirty"
echo "android-release.test.sh: 6 cases passed"
```

- [ ] **Step 2: Run it to see it fail**

Run: `bash scripts/android-release.test.sh`
Expected: FAIL. `cp` cannot find `scripts/android-build-manifest.sh`.

- [ ] **Step 3: Write the build manifest script**

```bash
#!/usr/bin/env bash
# Record what produced the unsigned release APK. Run immediately after assembleRelease, on a clean tree.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
release="$repo_root/android/app/build/outputs/apk/release"
die() { echo "android build manifest: $1" >&2; exit 1; }
[[ -z "$(git -C "$repo_root" status --porcelain)" ]] || die "commit or stash changes before recording build inputs"
[[ -f "$release/app-release-unsigned.apk" ]] || die "run android/gradlew assembleRelease first"
cat > "$release/build-inputs.json" <<EOF
{
  "sourceCommit": "$(git -C "$repo_root" rev-parse HEAD)",
  "lockfileSha256": "$(sha256sum "$repo_root/android/app/gradle.lockfile" | cut -d ' ' -f 1)",
  "unsignedApkSha256": "$(sha256sum "$release/app-release-unsigned.apk" | cut -d ' ' -f 1)"
}
EOF
echo "Recorded build inputs: $release/build-inputs.json"
```

- [ ] **Step 4: Make signing verify the manifest**

In `android-release.sh`, after the `[[ -f "$unsigned" ]]` check (line 18), add:
```bash
manifest="$(dirname "$unsigned")/build-inputs.json"
[[ -f "$manifest" ]] || die "run scripts/android-build-manifest.sh after assembleRelease"
[[ -z "$(git -C "$repo_root" status --porcelain)" ]] || die "refusing to sign from a dirty worktree"
field() { sed -n "s/^  \"$1\": \"\([0-9a-f]*\)\",\{0,1\}$/\1/p" "$manifest"; }
[[ "$(field sourceCommit)" == "$(git -C "$repo_root" rev-parse HEAD)" ]] || die "HEAD is not the commit that built this APK"
[[ "$(field lockfileSha256)" == "$(sha256sum "$repo_root/android/app/gradle.lockfile" | cut -d ' ' -f 1)" ]] ||
  die "gradle.lockfile changed since the APK was built"
[[ "$(field unsignedApkSha256)" == "$(sha256sum "$unsigned" | cut -d ' ' -f 1)" ]] ||
  die "the unsigned APK is not the one the manifest recorded"
```
In the metadata heredoc, delete the `"worktreeDirty": …` line and add:
```
  "buildInputs": $(cat "$manifest"),
  "buildInputsVerified": true,
```
Also copy the manifest into the stage dir: `cp "$manifest" "$stage/"`.

- [ ] **Step 5: Wire the workflow and the runbook**

`android-release.yml` build job, after the "Test, lint, and build release" step:
```yaml
      - name: Record build inputs
        run: bash scripts/android-build-manifest.sh
```
Add `android/app/build/outputs/apk/release/build-inputs.json` to the "Preserve unsigned build and mapping" `path:` list. In `android/RELEASING.md`'s local-signing section, add the `bash scripts/android-build-manifest.sh` step between `assembleRelease` and `android-release.sh`.

In `package.json`'s `test` script, append `&& bash scripts/android-release.test.sh`.

- [ ] **Step 6: Run it**

Run: `bash scripts/android-release.test.sh && shellcheck scripts/android-release.sh scripts/android-build-manifest.sh scripts/android-release.test.sh`
Expected: `android-release.test.sh: 6 cases passed`, and shellcheck prints nothing.

- [ ] **Step 7: Commit**

`CHANGELOG.md`: `- Android signing refuses an APK whose recorded build inputs do not match the checkout.`

```bash
git add scripts/android-build-manifest.sh scripts/android-release.sh scripts/android-release.test.sh \
  package.json .github/workflows/android-release.yml android/RELEASING.md CHANGELOG.md
git commit -m "fix(android): signing verifies a build-time input manifest"
```

---

### Task 8: Pin every workflow action to a reviewed commit

**Closes:** *.github/workflows/android-release.yml:79 — mutable action tags, including in the job holding signing secrets.*

All four workflows (`android-release.yml`, `ci.yml`, `release.yml`, `triage.yml`) use `@vN` tags. The one exception is `anchore/scan-action`, already pinned at `android-release.yml:44`. Follow that line's form: `uses: owner/repo@<40-hex> # vX.Y.Z`.

**Files:**
- Modify: `.github/workflows/{android-release,ci,release,triage}.yml` (`uses:` lines)
- Create: `scripts/workflow-pins.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// scripts/workflow-pins.test.ts
import { expect, test } from "bun:test";
import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

const dir = resolve(import.meta.dir, "../.github/workflows");

test("every action is pinned to a full commit SHA with its version as a comment", () => {
  const loose = readdirSync(dir).filter((f) => f.endsWith(".yml")).flatMap((f) =>
    readFileSync(resolve(dir, f), "utf8").split("\n")
      .map((line, i) => ({ where: `${f}:${i + 1}`, line: line.trim() }))
      .filter(({ line }) => /^(- )?uses: /.test(line) && !/@[0-9a-f]{40} # v\S+$/.test(line)),
  );
  expect(loose).toEqual([]);
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `bun test scripts/workflow-pins.test.ts`
Expected: FAIL, listing every `@v4`, `@v3`, `@v2` and `@v7` line.

- [ ] **Step 3: Resolve each tag to its commit**

```bash
for ref in actions/checkout@v4 actions/checkout@v7 actions/setup-java@v4 actions/setup-node@v7 \
  actions/upload-artifact@v4 actions/download-artifact@v4 actions/labeler@v7 \
  oven-sh/setup-bun@v2 android-actions/setup-android@v3; do
  repo=${ref%@*}; tag=${ref#*@}
  full=$(gh api "repos/$repo/releases" --jq "[.[] | select(.tag_name|startswith(\"$tag.\"))][0].tag_name")
  sha=$(gh api "repos/$repo/commits/$full" --jq .sha)
  echo "$ref -> $repo@$sha # $full"
done
```
Each line gives the replacement. Read each action's release notes for the resolved version before pinning it; this is the review the finding asks for. If a `@v7` tag does not resolve (no such release exists), pin the newest release that does, and make the two workflows agree.

- [ ] **Step 4: Replace the lines**

Edit each `uses:` line to the printed form, keeping its indentation and leading `- `.

- [ ] **Step 5: Run the test and actionlint**

Run: `bun test scripts/workflow-pins.test.ts && actionlint .github/workflows/*.yml`
Expected: `1 pass`, and actionlint prints nothing.

- [ ] **Step 6: Commit**

`CHANGELOG.md`: `- Every workflow action is pinned to a reviewed commit.`

```bash
git add .github/workflows scripts/workflow-pins.test.ts CHANGELOG.md
git commit -m "ci: pin every action to a reviewed commit SHA"
```

---

### Task 9: Document what paid triage sends, and cap its spend

**Closes:** *.github/workflows/triage.yml:64 — enabling triage sends issue/PR text and changed filenames to OpenRouter.*

The `classify` job runs only when `vars.COLLIE_LLM_TRIAGE == 'true'`. The job runs `.github/scripts/triage.mjs` with `OPENROUTER_API_KEY`.

**Files:**
- Modify: `.github/workflows/triage.yml:57-64` (comment block above `classify`)
- Modify: `CONTRIBUTING.md` (`## Issues and review`, one paragraph)

- [ ] **Step 1: Confirm what the script sends**

Run: `grep -nE "title|body|filename|files|fetch\(|openrouter" .github/scripts/triage.mjs`
Expected: the lines that assemble the request payload. List exactly the fields they include (for example title, body, changed filenames) and write that list into the comment and the paragraph below. Do not describe fields the script does not send.

- [ ] **Step 2: Write the disclosure**

Above `classify:` in `triage.yml`:
```yaml
  # Opt-in (repo variable COLLIE_LLM_TRIAGE=true). When on, each new issue or PR sends its
  # <fields from Step 1> to OpenRouter under the OPENROUTER_API_KEY account, and OpenRouter
  # forwards them to the configured model's provider. Before enabling: set a credit limit on that
  # key at https://openrouter.ai/settings/keys so a flood of issues cannot run up a bill.
```
In `CONTRIBUTING.md` → `## Issues and review`, add:
```markdown
> **Note.** Automatic labelling is off. If the maintainer turns it on, a new issue's or pull
> request's <fields from Step 1> are sent to OpenRouter for classification. Keep private
> content out of issues either way.
```

- [ ] **Step 3: Set the cap before any opt-in**

Chris sets a credit limit on the OpenRouter key in its dashboard. Then verify the switch is still off:
Run: `gh variable list -R Buckeyes22/collie | grep COLLIE_LLM_TRIAGE || echo "unset (triage off)"`
Expected: `unset (triage off)`, or a value other than `true`.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/triage.yml CONTRIBUTING.md
git commit -m "docs: disclose what opt-in triage sends to OpenRouter"
```

---

### Task 10: Replace operational metadata with examples

**Closes:** *android/validation/hardening/README.md:64, .env.example:165, ANDROID_TWA_SPEC.md:39 — operational addresses, hostnames and personal paths remain in evidence, templates and plans.*

Running `git grep -lE '/home/chris|\.tail[0-9a-f]{6}\.ts\.net|192\.168\.[0-9]+\.[0-9]+'` over the tree finds 23 files. Task 4 handles the Android sources and tests. This task handles the docs and evidence. The private originals do not need to stay in the tree: their full text stays in Git history, and per D1 that history stays public anyway. Any private evidence that must be kept separately goes to the brain wiki via `mcp__brain-curator__submit_import`, never into this repo.

**Files (exclusive):** `android/validation/hardening/README.md`, `android/validation/hardening/{evidence.json,history-assets-review.md,lifecycle-review.md,ui-review.md}`, `.env.example`, `ANDROID_TWA_SPEC.md`, `ANDROID_TWA_IMPLEMENTATION_PLAN.md`, `ANDROID_NATIVE_IMPLEMENTATION_PLAN.md`, `ANDROID_CLAUDE_PANE_UX_PROPOSAL.md`, `android/acceptance/2026-09-11-ux-walk.md`, `docs/superpowers/plans/2026-09-10-android-claude-pane-ux.md`, `docs/superpowers/plans/2026-09-18-android-diagnostics.md`, `.superpowers/lanes/FIX/report.md`, `cli/remote.ts`, `web/src/fixtures/panes/claude--soft-wrapped-paragraph.txt`, and the bridge/cli test files in that grep output.

**Substitution table** (apply verbatim everywhere):

| Private value shape | Replacement |
| --- | --- |
| `<host>.<tailnet>.ts.net` | `collie.example-tailnet.ts.net` |
| `192.168.x.y[:port]` | `192.0.2.10[:port]` (RFC 5737 documentation range) |
| `/home/chris/…` | `$HOME/…` in commands, `/home/operator/…` in fixtures |
| lab host names (`z2`, the workstation name) | `lab-host`, `workstation` |
| `ssh -F … chris@<addr>` | `ssh lab-host` |

- [ ] **Step 1: Write the failing check** (append to `scripts/notices.test.ts`)

```ts
  test("tracked text carries no maintainer path, tailnet host or LAN address", () => {
    const { execSync } = require("node:child_process") as typeof import("node:child_process");
    const hits = execSync(
      "git grep -lIE '/home/chris|\\.tail[0-9a-f]{6}\\.ts\\.net|192\\.168\\.[0-9]+\\.[0-9]+' || true",
      { cwd: root, encoding: "utf8" },
    );
    expect(hits).toBe("");
  });
```
`-I` skips binaries, so the font subsets that match by accident (`nerd-symbols-3.5.0-spua.woff2`) are excluded.

- [ ] **Step 2: Run it to see it fail**

Run: `bun test scripts/notices.test.ts`
Expected: FAIL, listing the files above.

- [ ] **Step 3: Apply the table**

For each file, edit by hand rather than with a blind `sed`, so a test's expected value moves with its input. The specific cases:
- `.env.example:165`: `# COLLIE_TAILSCALE_HOSTS=collie.example-tailnet.ts.net,100.64.0.1,[fd7a::1]`.
- `android/validation/hardening/README.md:64-75`: replace the SSH block with `ssh lab-host 'hostname; whoami'`. Replace the doclayer wiki citation with "lab capacity: live checks on 2026-10-07".
- `ANDROID_TWA_SPEC.md` and `ANDROID_TWA_IMPLEMENTATION_PLAN.md`: every origin becomes `https://collie.example-tailnet.ts.net`. Add a top line: `> **Note.** Superseded by ADR 0036; endpoints are examples.`
- `history-assets-review.md`: turn absolute `/home/chris/git/collie/` links into repo-relative links.
- `.superpowers/lanes/FIX/report.md`: this is a lane scratch file and should not be tracked. Ask Chris for a yes to `git rm --cached .superpowers/lanes/FIX/report.md` and adding `.superpowers/` to `.gitignore`. That is a deletion from the tree, so it is listed here for approval.

- [ ] **Step 4: Run the checks**

Run: `bun test scripts/notices.test.ts && bun test ./bridge ./cli 2>&1 | tail -3 && bun scripts/check-doc-links.ts`
Expected: notices pass, the bridge/cli suites report `0 fail`, and doc links pass.

- [ ] **Step 5: Commit**

```bash
git add -A android/validation .env.example ANDROID_*.md android/acceptance/2026-09-11-ux-walk.md \
  docs/superpowers/plans cli bridge web/src/fixtures scripts/notices.test.ts .gitignore
git commit -m "docs: replace operational hosts, addresses and paths with examples"
```
If `cli/remote.ts` changed, add a `CHANGELOG.md` line: `- Example hosts in collie remote's help use documentation names.`

---

### Task 11: Replace real-session screenshots with synthetic captures

**Closes:** *history-assets-review.md:47 — acceptance screenshots contain real terminal conversations and unrelated project context.*

Thirty-two PNGs sit in `android/acceptance/`. The review names seven that contain real pane transcripts, local paths or device labels. Only `ANDROID_CLAUDE_PANE_UX_PROPOSAL.md` and `docs/superpowers/plans/2026-09-10-android-claude-pane-ux.md` reference captures. No README does.

**Files:** `android/acceptance/*.png`, `ANDROID_CLAUDE_PANE_UX_PROPOSAL.md`, `docs/superpowers/plans/2026-09-10-android-claude-pane-ux.md`

- [ ] **Step 1: Classify every capture**

Open each of the 32 PNGs with the Read tool. Sort each into one of two lists:
- **private**: it shows a real transcript, command, path, host, device label or pairing detail;
- **clean**: it shows only Collie chrome or synthetic content.

Record both lists in `history-assets-review.md`'s visual-asset section, one line per file.

- [ ] **Step 2: Stage a synthetic session**

On a disposable lab emulator (`scripts/android-emulator-lab.sh validate …` profile `36-phone`), run a throwaway Collie bridge and Herdr workspace:
```bash
export HOME="$(mktemp -d)"; mkdir -p "$HOME/demo-app" && cd "$HOME/demo-app" && git init -q
herdr workspace create demo --cwd "$HOME/demo-app"
```
In it, open one shell pane that runs `cat web/src/fixtures/panes/*.txt`, and one Claude pane asked: "List the files in this empty repo and suggest a README outline." Pair the emulator app to that bridge.

- [ ] **Step 3: Recapture every private screen**

For each private capture, reproduce the same screen (mirror, history, disconnect sheet, upload draft, transcript, reflow, panel mirror) and save it under the **same filename**, so references keep working:
`adb exec-out screencap -p > android/acceptance/<same-name>.png`
Then read each new capture back with the Read tool. Confirm it shows no host, path outside `$HOME/demo-app`, or real conversation.

- [ ] **Step 4: Update the descriptions**

Every sentence in `ANDROID_CLAUDE_PANE_UX_PROPOSAL.md:11,33,114,161` that describes what a capture shows must still be true. Rewrite any that describe the old content. Drop the `/tmp/s25/` location note at line 161, because it is private.

- [ ] **Step 5: Verify and commit**

Run: `bun scripts/check-doc-links.ts && git status --short android/acceptance | wc -l`
Expected: links pass, and the count equals the number of private captures.

```bash
git add android/acceptance ANDROID_CLAUDE_PANE_UX_PROPOSAL.md docs/superpowers/plans/2026-09-10-android-claude-pane-ux.md android/validation/hardening/history-assets-review.md
git commit -m "docs(android): acceptance captures use a synthetic session"
```

---

### Task 12: Fix the publication ref set and review its graph

**Closes:** *history-assets-review.md:7 — current-file cleanup cannot clear retained commits, other branches or author emails; select the exact publication refs.* Also closes the remaining half of the screenshot finding: an explicit decision about history.

**Files:** `android/validation/hardening/history-assets-review.md` (new `## Publication ref set` section, and the disposition line)

- [ ] **Step 1: Unshallow and list the remote's refs**

Run: `git fetch --unshallow origin && git ls-remote origin | awk '{print $2}' | sort`
Expected: the full head, tag and pull ref list. The local checkout is shallow (`git rev-parse --is-shallow-repository` → `true`), so the review cannot cover history until this step runs.

- [ ] **Step 2: Apply D2**

After Chris says yes to the branch list in D2:
```bash
git push origin --delete audit/content-redaction commands/operator-rows fix/110-partial-arrival fix/122-public-url feat/android-twa
```
This step is destructive and leaves no remote copy. Run it only after the explicit yes. Pull refs (`refs/pull/*`) cannot be deleted by the owner, so record them as published.

- [ ] **Step 3: Scan exactly the published graph**

```bash
git log --format='%H' main $(git tag -l 'android-v*' 'v*') | sort -u | wc -l
git log --format='%ae' main $(git tag -l) | sort | uniq -c
python3 -I scripts/audit-android-history-assets.py --help
```
Run the audit script and Gitleaks against `main` plus the tags, with the same options the review records. The author-email list must show only the two expected addresses (the upstream author's and chris@lateapexllc.com). Both are already public on upstream and the fork.

- [ ] **Step 4: Record the decision**

Add this to `history-assets-review.md`:
```markdown
## Publication ref set

Published refs: `main`, tags `v*` (upstream server releases) and `android-v*`, and GitHub's
`refs/pull/*`. Deleted on <date>: <the D2 list>. Graph: <N> commits, scanned with
<audit script + Gitleaks versions>, <result>.

History decision (D1): the repository has been public since the fork was created, so earlier
captures and metadata are already mirrored by forks, clones and caches. They remain in
history; the tip carries synthetic captures and example addresses. No rewrite was performed.
```
Change the header `**Disposition:**` line to `PASS` for both items, citing this section.

- [ ] **Step 5: Commit**

```bash
git add android/validation/hardening/history-assets-review.md
git commit -m "docs(android): record the publication ref set and history decision"
```

---

### Task 13: Mark the preserved upstream README

**Closes:** *UPSTREAM_README.md:1 — opens as an unqualified Collie/PWA introduction.*

**Files:** `UPSTREAM_README.md:1`

- [ ] **Step 1: Add the banner under the H1**

```markdown
# Collie

> **Note.** This is upstream Collie's README, kept for reference. Collie's server and PWA come
> from [AltanS/collie](https://github.com/AltanS/collie); install them from there. This
> repository builds the native Android client. Start at [README.md](README.md) and
> [Getting started](android/GETTING_STARTED.md).
```

- [ ] **Step 2: Verify and commit**

Run: `bun scripts/check-doc-links.ts`
Expected: pass.

```bash
git add UPSTREAM_README.md
git commit -m "docs: mark UPSTREAM_README as upstream's server/PWA introduction"
```

---

### Task 14: Launch wording matches the published artifact

**Closes:** *README.md:47 — "no published Android release" and broad "device acceptance" wording will go stale at launch.*

This task's edits land **in the Task 18 release commit**, because they name the APK that commit publishes. Write them here; commit them there.

**Files:** `README.md:46-50`, `SECURITY.md:12-16,22`, `android/RELEASING.md:3,139-141`

- [ ] **Step 1: Prepare the README text**

Replace lines 46–50 ("The fork has **no published Android release yet**…") with:
```markdown
Download the APK from the
[latest Android release](https://github.com/Buckeyes22/collie/releases/latest). Verify it with
`sha256sum -c SHA256SUMS`; the signing certificate fingerprint is in `signing-certificate.txt`.
Before release, each APK passes the release workflow's unit tests, lint and dependency scan.
It also gets an install-and-launch smoke check on one device. The release notes list what that
check covered.
```

- [ ] **Step 2: Prepare SECURITY.md**

Delete the "Private vulnerability reporting must be enabled…" paragraph (Task 16 enables it). Replace "There are no published Android releases yet. Once distribution begins, security fixes target" with "Security fixes target".

- [ ] **Step 3: Align the release notes template**

In `android-release.yml`'s "Write release notes" heredoc, change `complete the device acceptance checklist before publishing` to `complete the artifact smoke check in android/RELEASING.md before publishing, and list what it covered here`.

- [ ] **Step 4: Verify** (at Task 18)

Run: `grep -nE "no published Android release|device acceptance must" README.md SECURITY.md || echo clean`
Expected: `clean`.

---

### Task 15: Code of conduct and contribution terms

**Closes:** *CONTRIBUTING.md:88 — no code of conduct or contribution-license/sign-off policy.*

**Files:** Create `CODE_OF_CONDUCT.md`; Modify `CONTRIBUTING.md` (new `## Contribution terms` section after `## Issues and review`)

- [ ] **Step 1: Adopt the Contributor Covenant (per D4)**

`curl -fsSL https://www.contributor-covenant.org/version/2/1/code_of_conduct/code_of_conduct.md -o CODE_OF_CONDUCT.md`
Replace its `[INSERT CONTACT METHOD]` with `chris@lateapexllc.com`.

- [ ] **Step 2: State the terms**

```markdown
## Contribution terms

Contributions are accepted under the repository's [MIT license](LICENSE). Sign off each commit
(`git commit -s`) to certify the [Developer Certificate of Origin](https://developercertificate.org/);
review checks for the `Signed-off-by` line. There is no CLA.

Participation follows the [code of conduct](CODE_OF_CONDUCT.md). Report conduct problems to
chris@lateapexllc.com; the maintainer moderates issues, pull requests and discussions.
```

- [ ] **Step 3: Verify and commit**

Run: `grep -c "INSERT" CODE_OF_CONDUCT.md; bun scripts/check-doc-links.ts`
Expected: `0`, and links pass.

```bash
git add CODE_OF_CONDUCT.md CONTRIBUTING.md
git commit -s -m "docs: adopt a code of conduct and DCO contribution terms"
```

---

### Task 16: Repository security settings

**Closes:** *Private vulnerability reporting disabled*, *main unprotected and no rulesets*, *Dependabot alerts disabled*.

Current state, read on 2026-10-08: `private-vulnerability-reporting` → `{"enabled":false}`; `rulesets` → `[]`; `branches/main/protection` → 404; `vulnerability-alerts` → 404; `dependabot_security_updates` → disabled.

This task changes outward-facing settings, so confirm with Chris before running it. Run it after Task 8 has merged to `main`, so the required checks exist under their final names.

- [ ] **Step 1: Enable private vulnerability reporting and Dependabot alerts**

```bash
gh api -X PUT repos/Buckeyes22/collie/private-vulnerability-reporting
gh api -X PUT repos/Buckeyes22/collie/vulnerability-alerts
gh api -X PUT repos/Buckeyes22/collie/automated-security-fixes
```
`automated-security-fixes` is the optional PR half. Skip that line if Chris declines it.

- [ ] **Step 2: Protect `main`**

```bash
gh api -X POST repos/Buckeyes22/collie/rulesets --input - <<'EOF'
{
  "name": "main",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "bypass_actors": [{ "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "pull_request" }],
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [
          { "context": "typecheck + tests" },
          { "context": "Native Android test + lint + debug build" } ] } }
  ]
}
EOF
```
The two contexts are the `name:` values of `ci.yml`'s `check` and `android` jobs. Admin bypass is limited to pull-request mode, so Chris still merges, but a direct push cannot skip CI.

- [ ] **Step 3: Protect release tags**

```bash
gh api -X POST repos/Buckeyes22/collie/rulesets --input - <<'EOF'
{
  "name": "release tags",
  "target": "tag",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["refs/tags/android-v*", "refs/tags/v*"], "exclude": [] } },
  "bypass_actors": [{ "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" }],
  "rules": [{ "type": "creation" }, { "type": "update" }, { "type": "deletion" }]
}
EOF
```
Only the admin role can create a release tag. Nobody can move or delete one.

- [ ] **Step 4: Verify**

```bash
gh api repos/Buckeyes22/collie/private-vulnerability-reporting
gh api repos/Buckeyes22/collie/vulnerability-alerts -i 2>&1 | head -1
gh api repos/Buckeyes22/collie/rulesets --jq '.[].name'
```
Expected: `{"enabled":true}`, `HTTP/2.0 204`, then `main` and `release tags`. Then open `https://github.com/Buckeyes22/collie/security/advisories/new` while signed out, or ask a second account to. The "Report a vulnerability" form must load.

---

### Task 17: The signing environment and a backed-up production key (BLOCKER for Actions APKs)

> **Note.** Outcome 2026-10-08: Steps 1–2 ran. The key was generated in `~/secure` and backed up to the Vaultwarden item "Collie for Android release key", and a restore was verified. Chris declined Steps 3–5 in favour of local signing: `android-v1.5.2` was signed with `scripts/android-release.sh` and published from this machine. The Actions sign job therefore stops at its missing-secrets check by design. Run Steps 3–5 to move signing into CI.

**Closes:** *No environment or signing secrets exist; android-release.yml:102 will refuse signing.*

`android-release.yml`'s `sign` job declares `environment: android-release` and reads five secrets: `COLLIE_ANDROID_KEYSTORE_BASE64`, `COLLIE_ANDROID_KEY_ALIAS`, `COLLIE_ANDROID_STORE_PASSWORD`, `COLLIE_ANDROID_KEY_PASSWORD` and `COLLIE_ANDROID_CERT_SHA256`. `gh api repos/Buckeyes22/collie/environments` returns no environments. Chris runs every step here himself. The key is his, and it must never pass through a session transcript.

- [ ] **Step 1: Generate the production key outside the repo** (Chris, in a local terminal)

```bash
mkdir -p ~/secure && chmod 700 ~/secure
keytool -genkeypair -v -keystore ~/secure/collie-release.jks -alias collie \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Collie for Android, O=Late Apex LLC"
keytool -list -v -keystore ~/secure/collie-release.jks -alias collie | grep 'SHA256:'
```
The fingerprint printed last is `COLLIE_ANDROID_CERT_SHA256`.

- [ ] **Step 2: Back it up before first use**

Store the keystore file and both passwords as one Vaultwarden item, with the `.jks` attached (`bw create attachment --file ~/secure/collie-release.jks --itemid <id>`). Restore it once into a temp dir and confirm `keytool -list` opens it. A lost key means no installed user can ever update.

- [ ] **Step 3: Create the restricted environment**

```bash
gh api -X PUT repos/Buckeyes22/collie/environments/android-release --input - <<'EOF'
{ "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true } }
EOF
gh api -X POST repos/Buckeyes22/collie/environments/android-release/deployment-branch-policies \
  -f name='android-v*' -f type=tag
```

- [ ] **Step 4: Store the secrets in that environment**

```bash
base64 -w0 ~/secure/collie-release.jks | gh secret set COLLIE_ANDROID_KEYSTORE_BASE64 --env android-release -R Buckeyes22/collie
gh secret set COLLIE_ANDROID_KEY_ALIAS --env android-release -R Buckeyes22/collie --body collie
gh secret set COLLIE_ANDROID_STORE_PASSWORD --env android-release -R Buckeyes22/collie
gh secret set COLLIE_ANDROID_KEY_PASSWORD --env android-release -R Buckeyes22/collie
gh secret set COLLIE_ANDROID_CERT_SHA256 --env android-release -R Buckeyes22/collie
```
The commands without `--body` prompt for the value, so the passwords never appear in shell history.

- [ ] **Step 5: Verify**

Run: `gh secret list --env android-release -R Buckeyes22/collie --json name --jq '.[].name' | sort`
Expected: exactly the five names. The values are not readable back by design.

---

### Task 18: Cut and verify the first release

**Closes:** the launch half of Task 14, and proves Tasks 1–7 and 17 end to end.

- [ ] **Step 1: Release commit**

Follow `android/RELEASING.md` → "Cut and review a release". In one commit, bump `versionName`/`versionCode` in `android/app/build.gradle.kts` and the matching pins in `scripts/check-android-native.ts:6-7`, move the `ANDROID_CHANGELOG.md` Unreleased lines into a dated section, and apply the Task 14 README, SECURITY.md and release-notes edits.

- [ ] **Step 2: Full gate, once**

```bash
bun run lint && bun run typecheck && (cd web && bun run typecheck) && bun run test 2>&1 | tail -5
(cd web && bun run test -- --reporter=dot 2>&1 | tail -10)
(cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug lintRelease assembleRelease 2>&1 | tail -20)
bun run check:android && bun scripts/android-dependency-notices.ts --check && bun scripts/check-doc-links.ts
```
Expected: every command exits 0, with `0 fail` in the Bun and Vitest tails and `BUILD SUCCESSFUL` from Gradle.

- [ ] **Step 3: Tag and wait for the draft**

```bash
git tag -a android-vX.Y.Z -m "Collie for Android X.Y.Z" && git push origin main android-vX.Y.Z
gh run watch -R Buckeyes22/collie "$(gh run list -R Buckeyes22/collie -w 'Android release' -L1 --json databaseId --jq '.[0].databaseId')"
```
Expected: both jobs succeed, and a draft release exists with the APK, `SHA256SUMS`, `signing-certificate.txt`, `release-metadata.json` (containing `"buildInputsVerified": true`), `build-inputs.json`, `sbom.cdx.json` and the six notice files.

- [ ] **Step 4: Smoke-check the artifact**

Run the four checks in `android/RELEASING.md` → "Initial artifact smoke check" on the S25 Ultra or a dedicated emulator. Confirm Settings → Open-source licenses shows Block, Inc. and the OkHttp line. Write what ran into the draft notes, then publish the draft.

---

### Task 19: Nerd Font subsets carry every family's license

**Closes:** *scripts/build-nerd-font.sh:37 — subsets include multiple icon families but carry only the Symbols font's MIT notice.*

`build-nerd-font.sh` subsets `SymbolsNerdFontMono-Regular.ttf` into `pua` (U+E000–F8FF) and `spua` (U+F0000–F1AFF). The first range holds Powerline, Devicons, Codicons, Font Awesome, Octicons, Seti, Weather, Pomicons, Font Logos and IEC Power Symbols. The second holds Material Design Icons. Line 47 copies only the patcher's MIT `LICENSE`. The v3.5.0 `license-audit.md` gives each family's own license.

**Files:**
- Modify: `scripts/build-nerd-font.sh:47`
- Create: `web/public/fonts/LICENSE-nerd-symbols.txt` (generated by the script), and replace the content of `web/public/fonts/LICENSE.txt` only if its header changes
- Test: `scripts/notices.test.ts`

- [ ] **Step 1: Read the audit's family table**

Run: `curl -fsSL https://raw.githubusercontent.com/ryanoasis/nerd-fonts/v3.5.0/license-audit.md | sed -n '1,80p'`
Expected: a table of glyph sets with license and source columns. Note every family whose codepoints fall in the two subset ranges. Note too which ones need attribution: CC BY 4.0 (Font Awesome), CC BY / CC BY-SA (Codicons, Weather Icons, as stated there), OFL, Apache-2.0 (Material Design Icons) and MIT.

- [ ] **Step 2: Write the failing test** (append to `describe("notices")`)

```ts
  test("the Nerd Font subsets ship each glyph family's license", () => {
    const text = read("web/public/fonts/LICENSE-nerd-symbols.txt");
    for (const family of ["Font Awesome", "Material Design Icons", "Codicons", "Octicons",
      "Devicons", "Powerline", "Seti", "Weather Icons"]) {
      expect(text).toContain(family);
    }
    expect(text).toContain("license-audit.md");
  });
```
Use the family names exactly as `license-audit.md` spells them; adjust the array to match Step 1.

- [ ] **Step 3: Run it to see it fail**

Run: `bun test scripts/notices.test.ts`
Expected: FAIL with `ENOENT` on `LICENSE-nerd-symbols.txt`.

- [ ] **Step 4: Teach the script to write the combined notice**

After `cp "$WORK/LICENSE" "$OUT/LICENSE.txt"` (line 47):
```bash
# The symbols font bundles glyph sets under their own licenses; the audit lists each one.
audit="https://raw.githubusercontent.com/ryanoasis/nerd-fonts/v$VERSION/license-audit.md"
{
  echo "Nerd Fonts Symbols v$VERSION: glyph families in the pua and spua subsets"
  echo "Source of this list: $audit"
  echo
  curl -fsSL "$audit"
  echo
  echo "Nerd Fonts patcher and symbols font: see LICENSE.txt (MIT)."
} > "$OUT/LICENSE-nerd-symbols.txt"
```
Then fetch the full text of each attribution-requiring license the audit cites, from the URLs it gives: CC BY 4.0 legal code, OFL 1.1 and Apache 2.0. Append each to the same file under a `== <license> ==` heading. Run `bash scripts/build-nerd-font.sh 3.5.0`. It regenerates the subsets byte-identically from the same release, so confirm with `git diff --stat web/public/fonts/*.woff2` (expected: no change) and commit only the license files.

- [ ] **Step 5: Run and commit**

Run: `bun test scripts/notices.test.ts && shellcheck scripts/build-nerd-font.sh`
Expected: pass, and no shellcheck output.

`CHANGELOG.md`: `- The bundled Nerd Font symbol subsets ship every glyph family's license.`

```bash
git add scripts/build-nerd-font.sh web/public/fonts/LICENSE-nerd-symbols.txt scripts/notices.test.ts CHANGELOG.md
git commit -m "fix(web): Nerd Font subsets carry each glyph family's license"
```

---

## Coverage map

| Finding | Task |
| --- | --- |
| shadcn notice (button.tsx:6) | 2 |
| Goose copyright (third_party_notices.txt:258) | 1 |
| Signing environment and secrets | 17 |
| Private vulnerability reporting | 16 |
| Acceptance screenshots | 11, 12 (D1) |
| Operational addresses and paths | 10, 4 |
| History, refs and author emails | 12 (D2) |
| Claude, pi, OMP provenance | 3 |
| Codex/OpenAI mark | 3 |
| Antigravity glyph | 3 |
| Nerd Font multi-family licenses | 19 |
| Branch protection and tag rulesets | 16 |
| Dependabot alerts | 16 |
| Signing provenance (android-release.sh:48) | 7 |
| UPSTREAM_README banner | 13 |
| README launch wording | 14, 18 |
| Action pinning | 8 |
| Code of conduct and DCO | 15 |
| Dependency inventory and notices screen | 5, 6 |
| Triage disclosure and spend cap | 9 |
