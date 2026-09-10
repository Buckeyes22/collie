package com.lateapex.collie.ui

/** Full native mirror of web/src/lib/agent-commands.ts. [ShippedAgentCommandsTest] guards drift. */
internal object ShippedAgentCommands {
    private fun catalog(rows: String): List<AgentCommand> = rows.trimIndent().lineSequence()
        .filter(String::isNotBlank)
        .map { row ->
            val fields = row.split('\t', limit = 4)
            require(fields.size == 4) { "Malformed shipped agent-command row" }
            val flags = fields[3]
            AgentCommand(
                command = fields[0],
                description = fields[1],
                takesArgument = 'a' in flags,
                argumentHint = fields[2],
                common = 'c' in flags,
                dangerous = 'd' in flags,
            )
        }.toList()

    private val commands = mapOf(
        "claude" to catalog("""
/compact	Summarize the conversation to free up context; optional focus	[instructions]	ac
/clear	Start a fresh conversation with empty context		cd
/model	Switch the model; opens a picker if no name given	[model]	ac
/resume	Resume a previous conversation by id, name, or picker	[session]	ac
/init	Generate a starter CLAUDE.md for this project		c
/review	Review a GitHub pull request by number (lists open PRs if none)	[PR]	ac
/status	Show version, model, account, and connectivity info		c
/usage	Show session cost, plan limits, and activity stats		c
/context	Visualize context-window usage with optimization hints		c
/memory	Edit CLAUDE.md memory files and auto-memory entries		c
/help	Show help and list available commands		c
/add-dir	Add an extra working directory for file access	<path>	a
/agents	Manage subagent configurations and view running agents		-
/branch	Fork the conversation here to explore a different direction	[name]	a
/btw	Ask a quick side question without adding it to history	<question>	a
/cd	Move the session to a new working directory	<path>	a
/code-review	Review the current diff for bugs and cleanups	[level]	a
/config	Open settings, or set a value with key=value	[key=value]	a
/copy	Copy the last assistant response to the clipboard	[N]	a
/cost	Show token cost and usage for the current session		-
/deep-research	Fan out web searches and synthesize a cited report	<question>	a
/diff	Open an interactive viewer of uncommitted changes		-
/doctor	Diagnose and verify your Claude Code installation		-
/effort	Set the model reasoning effort level	[low|medium|high|max]	a
/export	Export the conversation as plain text	[filename]	a
/fast	Toggle fast mode on or off	[on|off]	a
/feedback	Submit feedback or report a bug to Anthropic	[report]	a
/fork	Spawn a background subagent that inherits this conversation	<directive>	a
/goal	Set a completion condition; keep working until it is met	[condition|clear]	a
/hooks	View hook configurations for tool events		-
/ide	Manage IDE integrations and show connection status		-
/login	Sign in to your Anthropic account		-
/logout	Sign out from your Anthropic account		d
/loop	Run a prompt repeatedly on an interval (self-paced if none)	[interval] [prompt]	a
/mcp	Manage MCP server connections and auth	[subcommand]	a
/permissions	Manage allow, ask, and deny rules for tools		-
/plan	Switch into plan mode; optionally seed a description	[description]	a
/plugin	Manage plugins — list, install, enable, or disable	[subcommand]	a
/recap	Generate a one-line summary of the current session		-
/release-notes	View the changelog in a version picker		-
/rename	Rename the current session	[name]	a
/rewind	Roll back code and conversation to a checkpoint		d
/security-review	Analyze pending changes for security vulnerabilities		-
/simplify	Review changed code for cleanups and apply fixes	[target]	a
/skills	List available skills and toggle their visibility		-
/statusline	Configure the shell status line display	[description]	a
/tasks	View and manage background tasks for this session		-
/terminal-setup	Configure terminal keybindings (e.g. Shift+Enter)		-
/theme	Change the color theme		-
/vim	Toggle Vim editing mode for the prompt		-
/exit	Exit the CLI (detaches if attached to a background session)		d
        """),
        "codex" to catalog("""
/compact	Summarize history to free up context-window tokens		c
/clear	Reset output and start a new chat in this session		cd
/diff	Show the git diff of the working tree (incl. untracked)		c
/model	Switch the active model and reasoning effort	<model>	ac
/new	Start a fresh conversation without leaving the CLI		cd
/status	Show model, approval policy, writable roots, token usage		c
/review	Request a code review of the current working tree		c
/mention	Attach specific files or folders to the context	<file>	ac
/permissions	Adjust which actions Codex can take without asking		c
/resume	Reload a previously saved conversation		c
/init	Generate an AGENTS.md scaffold in this project		-
/plan	Enter plan mode to propose a strategy before running	[prompt]	a
/goal	Set, pause, resume, or clear a long-running objective	[objective]	a
/approve	Retry an action denied by the approval reviewer		-
/fork	Clone the conversation into a new independent thread		-
/side	Open an ephemeral side conversation (alias: /btw)	[question]	a
/agent	Switch between active subagent threads		-
/copy	Copy the latest completed response to the clipboard		-
/mcp	List configured MCP tools (verbose for diagnostics)	[verbose]	a
/ide	Include currently open editor files in the context		-
/skills	Browse and apply task-specific skills		-
/personality	Choose Codex communication style	<style>	a
/fast	Toggle the fast service tier for the model		-
/vim	Toggle Vim keybindings for the composer		-
/theme	Preview and save a syntax-highlighting theme		-
/usage	View account token activity and usage stats		-
/ps	Show running background terminals and their output		-
/stop	Cancel all running background terminals		-
/logout	Sign out and clear stored credentials		d
/archive	Archive the current session and exit Codex		d
/delete	Permanently delete the current session		d
/quit	Exit the Codex CLI immediately (alias: /exit)		d
        """),
        "pi" to catalog("""
/compact	Manually compact context, optionally with instructions	[instructions]	ac
/new	Start a new session, clearing the current context		cd
/model	Switch the active model		c
/resume	Pick a previous session to resume		c
/session	Show session file, id, messages, tokens, and cost		c
/tree	Jump to any earlier point in the session and continue		c
/fork	Start a new session from an earlier user message		c
/share	Upload as a private gist with a shareable HTML link		c
/copy	Copy the last assistant message to the clipboard		c
/reload	Reload keybindings, extensions, skills, prompts, and context		c
/hotkeys	Show all keyboard shortcuts		c
/login	Sign in — manage OAuth or API-key credentials		-
/logout	Sign out and clear stored credentials		d
/scoped-models	Enable or disable models for Ctrl+P cycling		-
/settings	Thinking level, theme, message delivery, transport		-
/name	Set the session's display name	<name>	a
/trust	Save a project trust decision for future sessions		-
/clone	Duplicate the current active branch into a new session		-
/export	Export the session to HTML or JSONL	[format]	a
/import	Import and resume a session from a JSONL file	<file>	a
/changelog	Display version history		-
/quit	Quit pi		d
        """),
        "opencode" to catalog("""
/compact	Compact (summarize) the current session		c
/new	Start a new session (alias /clear)		cd
/models	List and switch between available models		c
/sessions	List and switch sessions (alias /resume)		c
/init	Guided setup to create or update AGENTS.md		c
/share	Share the current session via a link		c
/undo	Undo the last turn and revert file changes (Git-backed)		cd
/redo	Redo a previously undone turn		c
/help	Show the help dialog		c
/export	Export the conversation to Markdown		c
/unshare	Stop sharing the current session		-
/editor	Open ${'$'}EDITOR to compose a message		-
/details	Toggle visibility of tool-execution details		-
/thinking	Toggle visibility of model reasoning blocks		-
/themes	Browse and switch the UI theme		-
/connect	Add a provider and configure its API key		-
/exit	Quit opencode (alias /quit, /q)		d
        """),
        "omp" to catalog("""
/new	Start a new session		cd
/branch	Create a new branch from a previous message		c
/fork	Create a new fork from a previous message		c
/drop	Delete the current session and start a new one		d
/plan-review	Review the current plan; needs plan mode active		-
/compact	Compact the whole conversation to reclaim context		c
/shake	Drop heavy tool results from context without a full compact	[images]	ac
/model	Open the provider/model picker		-
/settings	Open the settings panel		-
/resume	Open the session picker		-
        """),
        "agy" to catalog("""
/plan	Switch into plan mode to outline steps before execution	[goal]	ac
/grill-me	Interactive interview to resolve ambiguity and align on design		c
/learn	Persist lessons, corrections, and project workflows	[lesson]	ac
/compact	Summarize conversation history to free up context		c
/clear	Clear conversation history and start fresh		cd
/model	Switch active model or reasoning effort	[model]	ac
/status	Show session stats, model, and connectivity status		c
/subagents	View and manage active background subagents		c
/skills	List and manage available skills		-
/rules	View loaded project rules and instructions		-
/mcp	List and manage Model Context Protocol servers	[server]	a
/hooks	View configured event hooks and triggers		-
/review	Review uncommitted changes in current workspace		-
/diff	Show git diff of modified files		-
/doctor	Diagnose installation, tools, and system health		-
/theme	Change syntax and terminal theme		-
/help	Show help and list available commands		-
/exit	Exit the AGY CLI (alias: /quit)		d
        """),
        "grok" to catalog("""
/new	Start a fresh session and clear the conversation		cd
/compact	Compress history to reclaim context; optional focus note	[context]	ac
/resume	Open the session picker to reload a previous session		c
/model	Switch model; optional effort as a second argument	[name] [effort]	ac
/copy	Copy the most recent response, or the Nth, or write a file	[N|file]	ac
/context	Show how the context window is being used		c
/rewind	Roll the conversation back to an earlier turn		cd
/help	Show help and list available commands		c
/effort	Set reasoning effort on the current model	[low|medium|high|xhigh]	a
/plan	Enter plan mode; optionally seed a description	[description]	a
/always-approve	Skip permission prompts, or turn that mode back off		d
/auto	Classifier-approve safe tools, or turn that mode back off		d
/rename	Rename the current session	[title]	a
/doctor	Diagnose the terminal session and list available fixes		-
/export	Export the conversation to a file or the clipboard		-
/delete	Delete this session's history after confirm		d
/quit	Quit the application		d
        """),
    )

    fun forAgent(canonicalAgent: String): List<AgentCommand> = commands[canonicalAgent].orEmpty()
}
