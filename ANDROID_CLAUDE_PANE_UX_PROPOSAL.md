# Android — the Claude pane is a terminal dump; make the transcript the body

Status: **Proposal** (2026-09-10). Written from the S25 Ultra walk against a throwaway Claude
session (`collie-app › app-testing`). Decision pending; nothing here is built yet except item 3.

## 1. What the phone shows today

`android/acceptance/2026-09-10-claude-pane-mirror.png` is a Claude pane on the S25 Ultra after a
short conversation. It is readable, and it is not an acceptable Claude Code display. Read as a
whole it is a terminal dump with a chat composer under it:

- **Double wrapping.** Claude renders into a terminal about 100 columns wide and wraps its own
  prose there, indenting continuation lines by two spaces. The phone shows about 65 columns at
  10 sp and wraps every one of those lines again. Orphan fragments ("rather than re-arguing it,
  which is") sit on their own line, insight boxes lose their rules, bullets get a continuation
  indent mid-sentence.
- **The statusline is raw.** "121350 tokens" floats alone; the git branch `/rc` is orphaned onto
  its own line by the same re-wrap; "bypass permissions on (shift+tab to cycle) · ← for agents" is
  a mono paragraph. The web client re-surfaces these rows as styled chrome under the mirror.
- **Tool rows lose their shape.** "● Read(…)" with its "⎿ Read 519 lines" child is a tree in the
  terminal. After wrapping, and in the system mono face, the connectors sit at odd offsets and the
  path is cut mid-word.
- **The pane title appears twice**, in the header and again as the collapsed tab-strip label
  directly beneath it.
- **The user turn is a grey slab** with a faint marker, indistinguishable from a selection
  artefact. Assistant prose, insight boxes and tool output all render in one weight and size.
- **It is a light-grey mono wall.** Claude's own colour cues survive, but at 10 sp on a 6.9 inch
  screen the transcript reads as a log file, not a conversation.

`android/acceptance/2026-09-10-claude-pane-history.png` is the same pane's History screen, fed by
Claude's session log through `GET /api/pane/:id/history`: user turns as cards with a name and
time, assistant prose in the app face, tool calls as collapsible rows, a match counter and
user-turn navigation. It looks like a native app. The two screens come from the same session;
only the source differs.

## 2. Why the mirror is the wrong default for an agent pane on a phone

The mirror is Herdr's already-rendered grid (ADR 0008: Collie runs no terminal emulator). That is
the right thing to show when the grid *is* the information: a shell, a menu or dialog, an agent
whose log Collie cannot read, or when the operator asks for raw output. For a Claude or Codex
conversation the grid is a lossy projection of the session log, rendered for a 100-column window
the phone does not have. Every defect in §1 follows from showing that projection on a 65-column
screen. Fixing them one at a time (reflow, statusline row, title dedupe, turn styling) yields a
tidier log file, not a conversation.

## 3. Options

| # | Option | What you get | Cost |
| --- | --- | --- | --- |
| 1 | **Transcript body for agent panes** (recommended) | The History screen's rendering as the live pane body, updating as the log grows; mirror only for dialogs, shells and Raw | Days: live journal polling, scroll/anchor behaviour, the switch to the mirror when a dialog is up |
| 2 | Fit the terminal's columns to the screen | Pixel-faithful grid at ~6.5 sp | Hours; unreadable without zoom |
| 3 | Reflow soft wraps + statusline chrome row | A tidier mirror at 10 sp; no orphans, styled status | A day; still a log file |

Option 3 is needed under option 1 as well, because the mirror still appears for dialogs and
shells, so it is not an alternative but a floor. Option 2 is listed for completeness and not
recommended.

## 4. Recommendation: option 1, with option 3 as the floor

**Item 1 — transcript body.** For a pane whose agent has a journal adapter (`claude`, `codex`,
`pi`; `bridge/journal/registry.ts`), the pane body is the transcript, rendered by the same code
that renders `HistoryActivity` today, kept live by polling `GET /api/pane/:id/history` with its
cursor at the pane's poll cadence. The composer, the mode row, the status label and the semantic
prompt panel stay exactly where they are.

**Item 2 — when the mirror shows instead.** The mirror replaces the transcript body while any of
these hold, and returns when none do:
- the semantic parser reports a dialog, menu or autocomplete surface (the grammar in
  `AgentSemanticParser` already decides this);
- the pane's agent has no journal adapter, or the history route answers that it has no session;
- the operator turned on **Raw terminal** in the Display drawer.

A row above the body says which is showing and why, with a one-tap switch to the other. Find,
Zen, tap-to-type and the buffer affordances follow whichever body is on screen.

**Item 3 — the mirror floor.** Two changes to the mirror itself, for the cases where it shows:
- **Reflow soft wraps.** Join a line that reaches the grid's right margin with the indented
  continuation directly under it, then let the phone wrap the joined paragraph once. Tables,
  code blocks, rules and the input box are untouched. The grid width comes from the read itself.
  Both the native chrome filter and the web adapter take this, since the PWA has the same defect
  on a phone.
- **Statusline chrome row.** The rows the chrome filter already peels off the box (token count,
  cwd and branch, permission mode) render as one compact styled row under the mirror, as the web
  does, instead of as raw mono lines that re-wrap.

**Item 4 — small fixes that stand alone.**
- Do not repeat the pane title as the collapsed tab-strip label; show the tab count or nothing.
- Give the user turn a real card treatment in the mirror (background, marker, spacing) so it is
  not read as a selection.

## 5. What does not change

- No terminal emulator on either side (ADR 0008). Reflow and the status row are presentation over
  the grid Herdr rendered; nothing is re-interpreted from bytes.
- The journal stays the only place a client-supplied value becomes a path, and even there it is a
  pane id (`bridge/journal/files.ts`). The transcript body adds no route and no field.
- Prompt dialogs keep their verified key sequences and bindings; the transcript never types.
- The web client keeps its mirror-first pane. This proposal is for the phone, where the screen
  is the constraint. If it proves out, the same transcript body is available to the PWA.

## 6. Acceptance

- The pane in `2026-09-10-claude-pane-mirror.png`, re-captured after the work, shows the
  conversation as cards and prose with no line wrapped twice.
- Raising an AskUserQuestion from the phone switches the body to the mirror with the prompt panel,
  answering it switches back, both without leaving the pane (the re-walk ledger's dialog steps).
- A shell pane, and a pane whose agent has no journal, look exactly as they do today.
- Raw terminal on shows the mirror with reflowed prose and a status row; Raw off returns.
- The re-walk ledger's 186 steps still pass on the S25 Ultra.

## 7. Open questions for the operator

1. Option 1 with option 3 as the floor, or option 3 alone?
2. When a dialog is up, should the transcript stay visible above the prompt panel (mirror only
   for the dialog region), or should the whole body switch to the mirror? The first reads better;
   the second is simpler and is what the web does today.
3. Should the transcript body be the default for Codex too, or Claude first?
