# AGENTS.md

Guidance for AI coding assistants (and human contributors) working in this repository.

> Li Code was built with heavy use of AI coding tools. This file keeps an assistant productive in the codebase: how it's laid out, how to build it, and where the important logic lives.

## Project Structure

GroupId `com.licode`, artifactId `li-code`. Standard Maven layout: `src/main/java`, `src/test/java`. Java 21. No Maven Wrapper — use system `mvn`.

## Build & Run

```bash
# Compile
mvn clean compile

# Package (fat jar)
mvn clean package -DskipTests

# Run (after placing config at ~/.licode/config.yaml or ./.licode/config.yaml)
mvn exec:java -Dexec.mainClass="com.licode.app.LiCode"
```

## Architecture (package map)

```
com.licode
  .app/          LiCode — main() entry point
  .runtime/      LiRuntime — resident assembly/orchestration factory (create() wires everything once; ask() spins a lightweight Agent per user message)
  .agent/        Agent.agentLoop — the ReAct decision loop; StreamingExecutor — tool execution; AgentEvent
  .llm/          LlmClient interface + AnthropicClient / OpenAIClient / OpenAiCompatClient; StreamEvent ADT
  .conversation/ ConversationManager (working memory), Message, ToolUseBlock, ToolResultBlock, ThinkingBlock
  .session/      SessionManager — per-session JSONL archive / resume
  .memory/       MemoryManager — cross-session long-term memory
  .toolresult/   ContentReplacement* + ToolResultBudget — long tool-result offload ("spill") with idempotent freeze
  .compact/      ContextCompactor — two-layer context management; RecoveryState — post-summary fidelity restore
  .tool/         Tool, ToolRegistry, ToolCategory; .tool/impl — Read/Write/Edit/Bash/Grep/Glob/ToolSearch/Worktree tools
  .mcp/          McpManager, McpToolWrapper — MCP servers (stdio/SSE/HTTP JSON-RPC)
  .skill/        Skill packs — catalog-on-startup, body-on-demand (LoadSkillTool)
  .subagent/     AgentTool — one-shot sub-agents with tool-whitelist filtering
  .worktree/     WorktreeManager — git-worktree file-level isolation for sub-agents
  .team/         Multi-agent team: TeamManager, Coordinator, FileMailBox, TmuxBackend
  .permission/   PermissionChecker — PermissionMode × ToolCategory matrix
  .hook/         HookEngine — 12 lifecycle events (pre/post tool use, turn, session, etc.)
  .plan/         Plan Mode — PlanFile, PlanModePrompt
  .prompt/       PromptBuilder, PromptSections, system prompt assembly
  .tui/ .gui/ .web/  Three front-ends: TUI4J terminal, JavaFX GUI, embedded HTTP+SSE web
```

See `docs/architecture.md` for the full data-flow, tool-execution pipeline, and failure-recovery design.

## Config

`config.yaml` (at `~/.licode/` or `./.licode/`) declares `providers` (name, protocol, base_url, model, optional inline `api_key`, thinking, context_window, max_output_tokens), `mcp_servers`, and `hooks`. When `api_key` is omitted it falls back to `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` env vars. See `.licode/config.example.yaml`.

## Conventions

- When calling SDKs, verify the latest API (signatures, parameter order, type names) via up-to-date docs rather than from memory.
- Local tools and MCP tools register into the same `ToolRegistry` and are called by name — the model sees no difference.
- Everything routes through `LiRuntime`; per-message state lives on the short-lived `Agent`, resident components live on the runtime.
