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

## Appendix A — the rest of the app through the same lens

Reviewed from the captures taken during the two S25 Ultra walks on 2026-09-10 (all under
`android/acceptance/2026-09-10-*.png`; the walk's full set sits in `/tmp/s25/` on ed8). The
question for each screen is the one asked of the Claude pane: does it read as a native app that
happens to talk to terminals, or as a terminal tool wearing an app's chrome? Functional defects
found on the walk are already fixed and committed; what follows is design.

### A.1 Dashboard (`2026-09-10-dashboard.png`)

The strongest screen. "Nothing needs you", then Needs input, Ready · unseen, Working, Recent,
Spaces: the triage order is right, the cards are quiet, and a blocked pane surfaces in red with a
badge on Spaces. What holds it back:

- **Titles are session-log names, truncated.** Four of five Working rows read "Codex session log
  01a0…" with the identifier cut by the ellipsis, so the only distinguishing text is the space
  beneath. The row should lead with the space and tab and demote the agent's auto-title, or
  truncate in the middle so the tail of the id survives.
- **Two ways to collapse, drawn two ways.** Recent has an up-arrow toggle at the left; Spaces has
  the same toggle plus a "+" at the right; Working has none. The sort control ("Newest ↓") is a
  text button in a header row otherwise made of icons.
- **The Spaces list is the app's longest surface and the least informative.** Fourteen rows of
  name, age and a pane count in a grey pill. Nothing says which panes are working, blocked or
  done inside a space, which is the question the operator has when scanning it.
- **The build stamp** ("Android app build 1.5.1-debug") sits in the scroll body of every list
  screen. It belongs in Settings only.

### A.2 Space (`2026-09-10-space.png`)

Three horizontal strips stack under the header before any content: SPACES chips, then tab chips,
then the overview title. That is 40% of the first screen spent on navigation the dashboard
already did. Specifics:

- **The spaces strip repeats the dashboard.** A "← Back" chip and every other space, when the
  operator just chose this one. A back arrow in the header and the current space's name is
  enough; the sibling switch belongs in the pane switcher, which already lists them.
- **The tab chips truncate at the screen edge** ("track-guide-prose" clipped, the "+" half
  visible) with no affordance that the row scrolls.
- **The overview card** ("StormLens · 3 tabs · 3 panes") restates the header.
- **Pane cards say "shell" three times.** A card should show something that distinguishes the
  panes: the last command, the cwd difference, or the tab name in the card rather than as a
  small grey label above it.

### A.3 Pane, shell (`2026-09-10-*pane*` captures 39, 47, 98)

Better than the Claude pane because a shell's grid is the information. Still:

- **The pane title appears twice**: header and collapsed tab-strip label directly beneath it.
- **Notices cover the mirror.** "Reply sent." and "Typed into terminal." float over the top rows of
  the transcript, hiding the prompt they confirm. They should sit in the composer's own band.
- **Three status vocabularies at once.** The header dot, the "SHELL / WORKING / BLOCKED / DONE"
  label above the mode row, and the dashboard's coloured rings all encode the same state with
  different words and colours.
- **Landscape** keeps the tab strip and the full composer, leaving five rows of terminal.

### A.4 Keys drawer (`2026-09-10-keys-drawer.png`)

Functionally complete and now correct; visually it is the busiest surface in the app. In one
drawer: a Keys/123 segment, a staged-chip row with Send keys and Clear, eight key buttons, a full
width Space bar, three modifier toggles, a Presets disclosure, a Function keys disclosure. Opened,
it covers two thirds of the screen and leaves one line of terminal. The modifiers read as plain
text ("⇧ Shift  Ctrl  Alt") until armed, so their tappability is not visible. A phone keyboard
solves the same problem with one row and a long-press: Ctrl and Alt as sticky keys on the mode
row, arrows and Esc/Tab/Enter as one compact row, Presets and F-keys behind a single "More".

### A.5 Display, Quick, Agent (captures 41, 54, 77)

- **Display** is a settings panel inside the composer: three switches and a stepper. Wrap and Raw
  are per-pane reading modes and belong in the pane's actions sheet; text size is already in
  Settings. Tap-to-type is the only thing that earns a toggle here, and it could simply be on.
- **Quick** shows one group, CONFIRM, with "y" and "n" as two wide buttons. On a shell that is
  fine; on an agent pane the useful quick replies are "yes", "continue", "no, stop" as words.
- **Agent commands** is a good sheet: search, common commands first, descriptions. It is the
  model the other drawers should follow.

### A.6 Switcher (`2026-09-10-switcher.png`)

Rows carry space · tab on one line and agent · title on the next, so the identifier walls from
the dashboard return here at three lines per row. The current pane is not marked. Working is a
plain grey heading; Recent is a collapsible pill; the two groups look like different controls.

### A.7 History (`2026-09-10-claude-pane-history.png`)

The best screen in the app and the argument of §4. One gap: it is reachable only by scrolling a
pane to its top and tapping "Show entire history". It deserves a place in the pane header.

### A.8 Settings (`2026-09-10-settings.png`)

Now coherent after the font removals: Appearance, Text size, Haptics, Zen mode, notifications,
Updates, Paired devices, Connection. Remaining:

- **Zen mode is a setting that adds a menu row**, explained in two lines. Either the row is
  always in the pane menu or the feature is not worth a switch.
- **Background notifications** is a card whose only content is "not configured on this build".
  A card that can do nothing should not render.
- **Paired devices** mixes the pairing form into the device list when unpaired. The form is the
  first thing an unpaired phone needs and should be its own card at the top, not a footer of the
  list it cannot yet join.
- **Connection** is diagnostics presented as settings: five label/value rows and a red
  "Disconnect this phone" text link. Diagnostics can collapse; the destructive action needs a
  button, not a link.

### A.9 Setup and pairing (captures 107, 109, 111, 124)

The setup screen is clean. Two issues with the state after "Connect read-only": the banner "Not
paired — pair this device in Settings" is the operator's only cue and it looks like a warning
strip rather than the next step; and the pairing form it leads to sits mid-list in Settings, so
the operator scrolls past every other card to find the code field.

### A.10 Updates (capture 91)

The preflight list is honest and useful. The card reads "Running 1.5.1 · Newest 1.8.0 · One update
folds in 1.5.2, 1.5.3, 1.5.4, 1.5.5, 1.5.6, 1.6.0, 1.7.0, 1.8.0": eight version numbers in a
sentence when "seven releases behind" would do. The disabled Update button now reads as disabled,
but nothing says why in one line; the reason is three red rows above it.

### A.11 Cross-cutting

- **Two title fonts.** Aldrich for the app face, the system sans for a handful of strings that
  arrive from views built in code, so headers and sheet titles do not always match.
- **Colour carries state without a legend.** Amber dot working, green ready, red blocked, grey
  idle, plus the same colours reused for banners (amber reconnecting, red unreachable, green
  connected). Consistent, but never explained anywhere on the phone.
- **Sheets and drawers coexist.** New Space, Rename, Pane actions, Switcher and Agent commands
  are bottom sheets with a handle and a title; Keys, Quick and Display are in-composer drawers
  with a × and a title. Two vocabularies for the same gesture.

### A.12 Order of work, if §4 is approved

1. §4 items 1–3 (transcript body, mirror switching, reflow + status row).
2. A.3 and A.4: one status vocabulary, notices into the composer band, the compact Keys row.
3. A.2: collapse the Space header to one strip.
4. A.1 and A.6: row titles that lead with the space, middle truncation, current-pane marker.
5. A.8 and A.9: pairing card first when unpaired, drop the empty notifications card, Zen row
   unconditional, disconnect as a button.
6. A.5 and A.11: fold Display into the actions sheet, unify sheets and drawers.

## Appendix B — second pass, every capture

Appendix A was written from a handful of captures and memory of the rest; that was not a review.
This pass opened all 176 captures from the day (about 70 distinct screen states) and records what
A missed. Cited captures are saved under `android/acceptance/`.

### B.1 Space (`2026-09-10-space-strip-missing-current.png`)

- **The spaces strip does not contain the current space.** Viewing `collie-ui-test`, the strip
  reads "← Back · StormLens · flock · pitwall · pen …" and the current space is off-screen to the
  right, unselected. A strip that exists to show where you are does not show where you are.
- The current space's chips carry status dots and agent icons on the Space screen but the strip
  chips carry only a dot, so the two rows encode the same panes two ways.

### B.2 Pane chrome (captures 29, 51, 55, 121, 207, 46)

- **The collapsed tab strip is a centred plain title.** After "Hide tabs", the strip becomes the
  pane name centred in the same weight as a heading, with no chevron or affordance; nothing says
  it re-expands on tap.
- **Confirmation notices are floating cards.** "Reply sent.", "ctrl+c sent." and "Typed into
  terminal." appear as a white elevated card with a drop shadow over the first rows of the mirror,
  overlapping the prompt they confirm. They read as a system dialog left behind.
- **Type mode says the same thing twice and hides its stop.** The field hint becomes "Typing
  directly into terminal…" and a second label "Direct terminal typing armed" appears under it,
  while the Send button, now a stop control, keeps the paper-plane icon.
- **An attached image is a file path in the draft** (`2026-09-10-upload-path-in-draft.png`): after
  the picker, the composer holds `/home/chris/.local/state/collie/uploads/wM_p1-….png` across three
  lines with "Image added — path in message." beneath. That is the bridge's storage location shown
  to the operator; an attachment should be a chip or thumbnail the send expands.
- **Staged key chips overflow with no cue.** Four chips fill the row and the fourth is clipped at
  the edge; the row scrolls, but nothing indicates it.
- **Presets expanded leaves zero terminal rows** (`2026-09-10-keys-presets-no-terminal.png`). The
  drawer, the mode row and the composer take the whole screen; the terminal the keys act on is not
  visible at all, so the effect of Ctrl R or Ctrl U cannot be seen without closing the drawer.
- **Tree connectors render with the wrong glyph.** Claude's "⎿" child marker draws as a hooked
  "⌊" with a gap in the system mono face, which lacks the box-drawing coverage. This is the one
  place a bundled mono face is warranted, not as a picker but as the mirror's face.

### B.3 Sheets (captures 63, 87, 117, 118, 106)

- **The Rename sheet is titled "Pane actions"**, not "Rename pane", and its field is empty rather
  than prefilled with the current name, so a one-word edit means retyping the name.
- **The Close confirm keeps the "Pane actions" title** while its last row turns solid red "Tap
  again to close"; the other three rows stay live during the confirm.
- **Worktree creation is three sheets for one input.** Repository choice (one option, `collie-app`),
  then a sheet whose only content is "Create new branch", then the branch field. The final sheet
  says nothing about where the worktree will be made or from which base.
- **Disconnect is a black button with red text** (`2026-09-10-disconnect-sheet.png`) under a body
  of grey prose. A destructive confirm needs a red fill or a red outline, not red on black.

### B.4 Settings and pairing (captures 107, 110, 115, 216, 217)

- **Two input styles for the same task.** The setup screen uses outlined boxes with floating
  labels; the Settings pairing form uses bare underlined fields. Same code, same label, two
  designs.
- **"Revoke" on this phone's own row** where the description says "Unpair this phone". The
  destructive verb should match the row.
- **Updates: "Remind me next digest" is plain text** beneath the disabled button, not visibly a
  control; "Details · 3 red ^" is a centred text toggle; and the three "Fix:" lines are server-side
  instructions (`git stash`, set an environment variable) shown on a phone that cannot act on them.

### B.5 Dashboard (captures 200, 219, 99)

- **The collapse icon rotates to the wrong direction.** Recent expanded shows "↑", collapsed shows
  "←"; a disclosure should be a chevron that turns, not an arrow that points left.
- **Landscape** centres a phone-width column with empty margins either side, which is acceptable,
  but the pane in landscape keeps the tab strip and full composer and leaves five terminal rows.

### B.6 Corrections to Appendix A

- A.3 claimed three status vocabularies; the captures show the header dot, the label above the
  mode row, the dashboard ring, and the Space chip dot: four.
- A.5 called Quick "fine on a shell"; capture 54 shows the drawer opening at two thirds of screen
  height for two buttons.

### B.7 Additions to the order of work

Insert after step 2 of A.12: the Space strip must include and select the current space; notices
move into the composer band; the attachment becomes a chip; Rename prefills and is titled; the
mirror gets a bundled mono face with box-drawing coverage. Steps 3 to 6 stand.
