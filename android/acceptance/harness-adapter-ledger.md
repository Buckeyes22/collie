# B09 harness adapter ledger

This is the source/interface snapshot for H12. It is not a support promise or a complete live vendor
acceptance report. The API is scoped to the owning runtime; it now returns bounded version output
for registered binaries and counts exact per-kind pane/session identities in the current owner
snapshot. A zero count means not observed in that snapshot, not unsupported. Those observations do
not prove version-matched capture compatibility, a journal/session protocol, semantic parity, or a
guarded send. `interface-ready` means a bounded producer can call the settled B34 port; it does not
mean B34 has mounted ingestion, recorder policy, storage or Crew blob authorization in this
dependency snapshot.

Cell order is **L/D/S/J/T/C/M**: launch, detection/state, session reference, journal, web semantic
dialog, web composer, media. `implemented` is limited to executable registration, an actual journal
reader, or an exact web adapter registry entry. It does not mean live acceptance. The media column
uses `interface-ready` for source adapters awaiting the owner port. `unverified` never means
unsupported.

| Kind | L / D / S / J / T / C / M | Evidence/version line |
| --- | --- | --- |
| `pi` | implemented / unverified / unverified / implemented / unverified / unverified / interface-ready | Screen 0.87.1 canary 2026-09-30; journal 0.87.1 canary 2026-09-30. |
| `claude` | implemented / unverified / unverified / implemented / implemented / implemented / interface-ready | Screen 2.1.291 capture 2026-10-06; journal 2.1.285 canary 2026-09-30. |
| `codex` | implemented / unverified / unverified / implemented / implemented / implemented / unverified | Screen and journal 0.159.2 canary 2026-10-01. |
| `gemini` | implemented / unverified / unverified / implemented / unverified / unverified / unverified | Launch CLI reference and `--version`; journal uses Google's current `chatRecordingTypes.ts` and recording-service contract (bounded JSONL messages, patches, order/removal snapshots and rewinds under `~/.gemini/tmp`). Live version-matched journal/canary acceptance remains unverified. |
| `cursor` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Exact `cursor-agent` executable and `--version` are documented by Cursor; no executable was present during owner preparation. |
| `devin` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Devin for Terminal documents exact `devin` executable; no version command or installed executable was established. |
| `agy` | implemented / unverified / unverified / unverified / implemented / implemented / unverified | Antigravity CLI 1.1.17 capture 2026-08-26. |
| `cline` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Preparation probe found Cline 3.0.69; launch-by-ID registers exact binary `cline`, while all non-launch dimensions remain unverified. |
| `omp` | implemented / unverified / unverified / implemented / implemented / implemented / interface-ready | OMP 18.4.10 Ask, note-editor, multi-select, bash/write approval, and switch captures; journal alias uses pi format, 18.1.19 sweep 2026-09-30. No current installed OMP binary was found in the recorded owner-host probe. |
| `mastracode` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Mastra Code docs document the `mastracode` command; no version command or installed executable was established. |
| `opencode` | implemented / unverified / unverified / implemented / implemented / implemented / interface-ready | 1.18.33 canary 2026-09-30; single-question captures include 1.18.31; journal adapter in current source. |
| `copilot` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Preparation probe found GitHub Copilot CLI 1.0.88; launch-by-ID registers exact binary `copilot`, while all non-launch dimensions remain unverified. |
| `kimi` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Preparation probe found Kimi CLI 0.40.1; launch-by-ID registers exact binary `kimi`, while all non-launch dimensions remain unverified. |
| `kiro` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Kiro CLI reference documents `kiro-cli` and `--version`; no executable was present during owner preparation. |
| `droid` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Factory Droid CLI reference documents `droid` and `-v`; no executable was present during owner preparation. |
| `amp` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Amp CLI docs document `amp` and `amp version`; no executable was present during owner preparation. |
| `grok` | implemented / unverified / unverified / implemented / implemented / implemented / unverified | Build 1.0.41 capture 2026-09-27 covers draft only; dialog grammar evidence is older 1.0.5; journal version is unverified. |
| `hermes` | implemented / unverified / unverified / implemented / unverified / unverified / unverified | Journal parser is unit-tested; no SessionDB sweep or verified executable version. `0.0.0` is a floor sentinel, not a vendor version. |
| `kilo` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Kilo CLI docs document exact `kilo` invocation; no version command or installed executable was established. |
| `qodercli` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Qoder CLI docs document `qoder` and `--version`; no executable was present during owner preparation. |
| `qwen` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Preparation probe found `qwen`, but `--version` printed no version; launch-by-ID registers the exact binary and all non-launch dimensions remain unverified. |
| `letta` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Letta Code docs document exact `letta` command; no version command or installed executable was established. |
| `maki` | implemented / unverified / unverified / unverified / unverified / unverified / unverified | Maki CLI docs document exact `maki` command; no version command or installed executable was established. |
| `muse` | implemented / unverified / unverified / implemented / implemented / implemented / interface-ready | UI captures 1.4.0 on 2026-09-26; journal format sweep 1.4.2 on 2026-10-02, explicitly unverified because no CI canary runs Muse. |

The launch table now has one exact documented command for each of the 24 H12 catalog identities.
For the 11 previously missing rows, registration was added from these official command references:
[Gemini CLI](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/cli-reference.md),
[Cursor CLI](https://docs.cursor.com/en/cli/overview),
[Devin for Terminal](https://docs.devin.ai/cli),
[Mastra Code](https://code.mastra.ai/), [Kiro CLI](https://kiro.dev/docs/cli/reference/cli-commands/),
[Factory Droid](https://docs.factory.com/droid-cli/overview), [Amp CLI](https://ampcode.com/docs/cli),
[Kilo CLI](https://kilo.ai/docs/getting-started/installing),
[Qoder CLI](https://docs.qoder.com/cli/installation), [Letta Code](https://docs.letta.com/quickstart),
and [Maki CLI](https://maki.sh/docs/cli/). The exact binaries are `gemini`, `cursor-agent`, `devin`,
`mastracode`, `kiro-cli`, `droid`, `amp`, `kilo`, `qoder`, `letta`, and `maki` respectively. No
installation or vendor process was started to validate them. Only CLIs with a documented bounded
version spelling are probed, using the per-row closed command enum; other launch registrations are
not version-probed. Registration does not claim any reader, detection, session, journal, semantic,
composer or media behavior. `executableFound` is a short-lived owning-host PATH probe and is not a
version probe. The frozen 24-kind inventory, accepted mux pane names and launch registrations are
separate lists even where their exact IDs overlap. `antigravity`
is an alias for the `agy` terminal agent in UI semantic dispatch, not a twenty-fifth H12 row.

Gemini journal source references, checked 2026-10-11: Google's
[session-management documentation](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/session-management.md)
documents project-scoped chats beneath `~/.gemini/tmp/<project_hash>/chats`; the current
[record types](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/services/chatRecordingTypes.ts)
and [recording service](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/services/chatRecordingService.ts)
define JSONL metadata/message/patch/rewind records. The Collie reader validates the full metadata
UUID after bounded discovery and reads only contained logs. `$patch.orderIds`, `$patch.removeIds`,
`$rewindTo`, and full `$set.messages` replacements reset the live position generation because the
existing UUID-only upsert protocol cannot express a move or deletion. This source-derived parser is
not a version-matched canary; live Gemini J acceptance remains open.

## Exact mux identities

The contract accepts each of the 24 catalog IDs plus the existing `antigravity` alias. Herdr
forwards its reported `agent` field byte-for-byte, and the capability route counts only exact live
identities and owner-reported session references. TUIOS remains strict: known adapter-specific
translations are explicit, including its documented `gemini-cli` integration ID to Herdr `gemini`
identity; other TUIOS manifest IDs pass through only when they exactly equal a registered Collie
canonical identity. Unknown and prefix-lookalike IDs still map to `shell` and do not retain a session
reference. This is name normalization, not evidence that a vendor hook, session protocol or reader is
installed on any particular runtime. The exact TUIOS source snapshot is
`/home/chris/git/collie/.project-docs/tuios/docs/AGENT_STATE.md` at revision
`de010d153531170bfe3dcdb8d57ccb1d74e3fef7` (fetched 2026-10-10).

## Parent's pre-task executable/version probe

The preparation record at
`/home/chris/git/collie/.project-docs/implementation-evidence/capability-backlog/B09/source-preparation.json`
records bounded PATH and `--version` checks before this implementation assignment. This table keeps
that host snapshot separate from the older fixture/canary versions above. It does not establish
current dialog, journal, composer, detection or media behavior; no vendor session was opened in B09.

| Kind | Preparation-time executable result |
| --- | --- |
| `pi` | present; 0.84.4 |
| `claude` | present; 2.1.296 |
| `codex` | present; 0.162.1 |
| `gemini` | not found |
| `cursor` | not found |
| `devin` | not found |
| `agy` | present; 1.3.1 |
| `cline` | present; 3.0.69 |
| `omp` | not found |
| `mastracode` | not found |
| `opencode` | present; 1.18.35 |
| `copilot` | present; 1.0.88 |
| `kimi` | present; 0.40.1 |
| `kiro` | not found |
| `droid` | not found |
| `amp` | not found |
| `grok` | present; 1.0.40 |
| `hermes` | present; 0.23.0 |
| `kilo` | not found |
| `qodercli` | not found |
| `qwen` | executable present; `--version` produced no version text |
| `letta` | not found |
| `maki` | not found |
| `muse` | present; 1.4.3 |

Per-kind detection and session-reference cells are reported from the current owner snapshot: detection
counts only exact `pane.agent == kind` identities; session references count a matching active pane or
an exited pane whose exact `sessionAgent == kind` carries an owner-reported `agentSession`. Each count
also requires the corresponding declared mux capability. A catalog name or executable presence does
not create a positive per-kind count. A current count does not establish a stable vendor resume
protocol; the source/version-specific session adapter and controlled lifecycle fixture are still
required.
The list of 24 canonical rows is in `bridge/harness-catalog.ts`; the independently maintained mux
identity and launch tables do not imply the same support dimensions.

## Current bounded owner-host version probe

The implementation probe captured before this launch-table expansion at
`/home/chris/git/collie-capability-b09/.project-docs/implementation-evidence/capability-backlog/B09/current-version-probe.json`
ran fixed `--version` argv only for the then-registered, present executables, with a 1.5 second
timeout, 4 KiB output ceiling, concurrency three, a minimal child environment, and the system temp
directory as cwd. It returned these current host versions on 2026-10-11 UTC:

| Kind | Found | Parsed version |
| --- | --- | --- |
| `claude` | yes | 2.1.296 |
| `codex` | yes | 0.162.1 |
| `opencode` | yes | 1.18.35 |
| `pi` | yes | 0.84.4 |
| `omp` | no | — |
| `grok` | yes | 1.0.40 |
| `hermes` | yes | unrecognized by semantic-version output policy |
| `muse` | yes | 1.4.4 |
| `agy` | yes | 1.3.1 |
| `cline` | yes | 3.0.70 |
| `copilot` | yes | unrecognized by semantic-version output policy |
| `kimi` | yes | 0.40.1 |
| `qwen` | yes | 0.23.0 |

These are runtime version observations only. No vendor session was launched; they do not certify
any of the seven H12 dimensions. The eleven newly registered commands were not part of that earlier
probe. Their current availability remains the preparation-time `not found` result above, and no
installation or second probe was performed in this stage. The current source only probes a version
when an exact version spelling is documented for that row: Gemini, Cursor, Kiro, and Qoder use
`--version`; Droid uses `-v`; Amp uses `version`. Rows without a documented spelling are never sent
a guessed version argument.

Resolved executable artifact SHA-256 values and byte sizes were checked separately at
`2026-10-11T03:21:32Z` and are recorded in
`/home/chris/git/collie-capability-b09/.project-docs/implementation-evidence/capability-backlog/B09/fixture-needs.json`.
These hashes identify the resolved executable file, not its package or installation tree. Hermes'
executable identity is recorded while its version remains unverified because the fixed probe timed
out. Copilot's bounded output is accepted as 1.0.88 by the corrected version parser. No raw output,
executable path, provider credential or session data is included. The same manifest defines the
secret-free, owner-controlled fixtures still needed for each of the 168 required cells; all remain
open until versioned behavior captures and their tests exist.

Journal rows backed by `bridge/journal/registry.ts` are Claude, Codex, Gemini, pi, OpenCode, Grok,
Hermes and Muse; `omp` resolves through the explicit pi alias. Journal image producer adapters are only
Claude, Muse, OpenCode, pi and OMP/pi. They validate bounded inline data or contained canonical blob
references and submit actual bytes through the injected owner port. The owner port must be supplied
by B34 before a blob reference is served. There is no second cache, retention policy or recorder in
B09.

The measured native additions in this patch are exact-shape OMP 17.2/18.4 resume, 18.4 single Ask/note,
multi-select and bash/write approvals, Muse single question/workspace trust/bash approval, and OpenCode
1.18 single question. They preserve pointer and checkbox read-back before key commits; OMP's multi-select
custom-answer row remains visible but noninteractive until its matching editor is captured. OpenCode focus
is read from the captured SGR chip style. Multi-question flows, overlays, dismissed/torn dialogs,
OpenCode question variants beyond single-select, Muse tasks/resume/composer drafts and unsupported
vendor grammars remain raw/locked. The native
composer path remains separately bounded to the existing Claude/Codex/Grok/Agy grammars.

## Provenance sources

- `web/src/lib/harness/verified-versions.json` records independent screen and journal version/date/how
  fields; `0.0.0` is an explicit unknown floor and is not reported as a real version.
- `web/src/lib/harness/registry.ts` is the exact web semantic/composer adapter registry.
- `bridge/harness-launch.ts` is the exact launch table; `bridge/journal/registry.ts` is the journal
  adapter/alias registry.
- B09 did not probe vendor installations, start vendor sessions or perform a live guarded send.
  Those rows remain acceptance-pending.
