package com.licode.subagent;

import com.licode.agent.Agent;
import com.licode.agent.AgentEvent;
import com.licode.conversation.ConversationManager;
import com.licode.conversation.ToolResultBlock;
import com.licode.hook.HookEngine;
import com.licode.llm.LlmClient;
import com.licode.permission.PermissionChecker;
import com.licode.permission.PermissionMode;
import com.licode.prompt.PromptBuilder;
import com.licode.tool.Tool;
import com.licode.tool.ToolCategory;
import com.licode.tool.ToolRegistry;
import com.licode.tool.ToolResult;
import com.licode.team.SpawnDispatcher;
import com.licode.team.TeamManager;
import com.licode.team.TeammateRunner;
import com.licode.team.TeamTools;
import com.licode.worktree.AgentWorktree;
import com.licode.worktree.WorktreeChanges;
import com.licode.worktree.WorktreeManager;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A tool that launches a sub-agent to handle a focused task. The sub-agent
 * runs in its own virtual thread with an isolated conversation and a filtered
 * tool registry.
 *
 * <p>Implements {@link Tool} so it can be registered in a {@link ToolRegistry}
 * and invoked by the parent agent via the standard tool-call mechanism.
 */
public class AgentTool implements Tool {

    private final LlmClient client;
    private final ToolRegistry parentRegistry;
    private final String protocol;

    private ModelResolver modelResolver;
    private Map<String, SubAgentSpec> agentSpecs;
    private Consumer<SubAgentProgress> progressListener;
    private SubAgentTaskManager taskManager;
    private ConversationManager parentConversation;
    private HookEngine hookEngine;
    private String workDir;
    private int contextWindow;
    private int maxOutput;

    // Runtime reference to the parent agent's event queue, for forwarding
    // sub-agent progress during synchronous execution.
    private BlockingQueue<AgentEvent> parentQueue;
    private WorktreeManager worktreeManager;
    private TeamManager teamManager;

    static final String FORK_BOILERPLATE_TAG = "<fork_boilerplate>";

    static final String FORK_BOILERPLATE = FORK_BOILERPLATE_TAG + """

            You are a forked worker process. You are NOT the main agent.
            Rules (non-negotiable):
            1. Do NOT fork again.
            2. Do NOT converse, ask questions, or request confirmation.
            3. Use tools directly: read files, search code, make changes.
            4. Stay strictly within your assigned task scope.
            5. Final report must be under 500 characters, starting with "Scope:".
            </fork_boilerplate>""";

    public AgentTool(LlmClient client, ToolRegistry parentRegistry, String protocol) {
        this.client = client;
        this.parentRegistry = parentRegistry;
        this.protocol = protocol;
    }

    public void setModelResolver(ModelResolver modelResolver) {
        this.modelResolver = modelResolver;
    }

    public void setAgentSpecs(Map<String, SubAgentSpec> agentSpecs) {
        this.agentSpecs = agentSpecs;
    }

    public void setProgressListener(Consumer<SubAgentProgress> progressListener) {
        this.progressListener = progressListener;
    }

    public void setTaskManager(SubAgentTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    public SubAgentTaskManager getTaskManager() {
        return taskManager;
    }

    public void setParentConversation(ConversationManager parentConversation) {
        this.parentConversation = parentConversation;
    }

    public void setHookEngine(HookEngine hookEngine) {
        this.hookEngine = hookEngine;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    public void setMaxOutput(int maxOutput) {
        this.maxOutput = maxOutput;
    }

    public void setParentQueue(BlockingQueue<AgentEvent> parentQueue) {
        this.parentQueue = parentQueue;
    }

    public void setWorktreeManager(WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
    }

    public void setTeamManager(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    // ---- Tool interface ----

    @Override
    public String name() {
        return "Agent";
    }

    @Override
    public String description() {
        var sb = new StringBuilder();
        sb.append("Launch a sub-agent to handle a complex task. Each agent runs independently ");
        sb.append("with its own context.\n\n");
        sb.append("Use this when a task benefits from focused, isolated work -- e.g., ");
        sb.append("researching a question, implementing a component, or reviewing code. ");
        sb.append("The sub-agent cannot see the current conversation.\n\n");
        sb.append("Available agent types:");

        if (agentSpecs != null && !agentSpecs.isEmpty()) {
            for (String name : AgentLoader.listNames(agentSpecs)) {
                SubAgentSpec spec = agentSpecs.get(name);
                sb.append("\n- ").append(name).append(": ").append(spec.description());
            }
        } else {
            sb.append("\n- general-purpose: Full tool access for multi-step tasks (default)");
            sb.append("\n- plan: Read-only tools for designing implementation plans");
            sb.append("\n- explore: Read-only search agent for locating code");
        }

        sb.append("\n\nWrite a detailed prompt explaining what the agent should do and why ");
        sb.append("-- it has no prior context.");
        return sb.toString();
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> inputSchema() {
        List<String> agentTypes;
        if (agentSpecs != null && !agentSpecs.isEmpty()) {
            agentTypes = AgentLoader.listNames(agentSpecs);
        } else {
            agentTypes = List.of("general-purpose", "plan", "explore");
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", Map.of(
                "type", "string",
                "description", "A short (3-5 word) description of the task"
        ));
        properties.put("prompt", Map.of(
                "type", "string",
                "description", "The task for the agent to perform"
        ));
        properties.put("subagent_type", Map.of(
                "type", "string",
                "enum", agentTypes,
                "description", "The type of specialized agent to use for this task"
        ));
        properties.put("model", Map.of(
                "type", "string",
                "description", "Optional model override for this agent"
        ));
        properties.put("run_in_background", Map.of(
                "type", "boolean",
                "description", "Set to true to run this agent in the background"
        ));
        properties.put("isolation", Map.of(
                "type", "string",
                "enum", List.of("none", "worktree"),
                "description", "Isolation mode. \"worktree\" creates a temporary git worktree so the agent works on an isolated copy of the repo."
        ));
        properties.put("team_name", Map.of(
                "type", "string",
                "description", "When provided, spawns the agent as a long-running teammate in this team. "
                        + "The teammate stays alive across multiple turns via mailbox polling. "
                        + "Use TeamCreate first to create the team."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", name());
        schema.put("description", description());
        schema.put("input_schema", Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("description", "prompt")
        ));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String description = getStringArg(args, "description");
        String prompt = getStringArg(args, "prompt");
        if (description == null || description.isEmpty() || prompt == null || prompt.isEmpty()) {
            return ToolResult.error("Error: description and prompt are required");
        }

        String subagentType = getStringArg(args, "subagent_type");
        String modelOverride = getStringArg(args, "model");
        String isolation = getStringArg(args, "isolation");
        String teamName = getStringArg(args, "team_name");

        // Team member path: team_name provided
        if (teamName != null && !teamName.isEmpty()) {
            return runAsTeammate(teamName, description, prompt, modelOverride, isolation);
        }

        // Fork path: no subagent_type specified
        if (subagentType == null || subagentType.isEmpty()) {
            return runFork(description, prompt, modelOverride);
        }

        // Resolve the spec
        SubAgentSpec spec = resolveSpec(subagentType);
        if (spec == null) {
            String available = (agentSpecs != null)
                    ? String.join(", ", AgentLoader.listNames(agentSpecs))
                    : "general-purpose, plan, explore";
            return ToolResult.error(
                    "Error: unknown agent type '%s'. Available: %s".formatted(subagentType, available));
        }

        boolean runInBackground = Boolean.TRUE.equals(args.get("run_in_background"));

        if (runInBackground) {
            return runAsync(spec, description, prompt, modelOverride, isolation);
        }
        return runSync(spec, description, prompt, modelOverride, isolation);
    }

    // ---- Execution paths ----

    private ToolResult runSync(SubAgentSpec spec, String description, String prompt, String modelOverride, String isolation) {
        // Worktree isolation: create isolated working directory
        AgentWorktree.Result wtResult = null;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            try {
                byte[] rndBytes = new byte[4];
                new SecureRandom().nextBytes(rndBytes);
                String hex = HexFormat.of().formatHex(rndBytes);
                String slug = "agent-a" + hex.substring(0, 7);
                wtResult = AgentWorktree.create(slug, worktreeManager.getProjectRoot(),
                        worktreeManager.getSymlinkDirs());
            } catch (Exception e) {
                return ToolResult.error("Error creating agent worktree: " + e.getMessage());
            }
        }

        ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec);
        LlmClient subClient = selectClient(spec.model(), modelOverride);

        Agent subAgent = new Agent(subClient, subRegistry, protocol);
        int maxTurns = spec.maxTurns() > 0 ? spec.maxTurns() : 200;
        subAgent.setMaxIterations(maxTurns);
        Path agentWorkDir = Path.of(workDir != null ? workDir : System.getProperty("user.dir"));
        if (wtResult != null) {
            subAgent.setWorkDir(wtResult.worktreePath());
            agentWorkDir = Path.of(wtResult.worktreePath());
        } else if (workDir != null) {
            subAgent.setWorkDir(workDir);
        }
        subAgent.setPermissionChecker(new PermissionChecker(PermissionMode.BYPASS, agentWorkDir));
        if (hookEngine != null) subAgent.setHookEngine(hookEngine);
        if (contextWindow > 0) subAgent.setContextWindow(contextWindow);
        if (maxOutput > 0) subAgent.setMaxOutput(maxOutput);

        ConversationManager conv = new ConversationManager();
        if (spec.systemPrompt() != null && !spec.systemPrompt().isEmpty()) {
            conv.addSystemReminder(spec.systemPrompt());
        }
        // Inject worktree notice before the task prompt
        if (wtResult != null) {
            String parentCwd = workDir != null ? workDir : System.getProperty("user.dir");
            String notice = AgentWorktree.buildNotice(parentCwd, wtResult.worktreePath());
            conv.addUserMessage(notice + "\n\n" + prompt);
        } else {
            conv.addUserMessage(prompt);
        }

        long startNanos = System.nanoTime();
        var output = new StringBuilder();
        int toolCount = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        BlockingQueue<AgentEvent> queue = subAgent.run(conv);

        while (true) {
            AgentEvent event;
            try {
                event = queue.poll(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitProgress(description, spec.name(), null, null, true, true, toolCount, elapsedSeconds(startNanos));
                return ToolResult.error("Agent interrupted");
            }

            if (event == null) {
                emitProgress(description, spec.name(), null, null, true, true, toolCount, elapsedSeconds(startNanos));
                return ToolResult.error("Agent timed out waiting for events");
            }

            switch (event) {
                case AgentEvent.StreamText st -> {
                    output.append(st.text());
                    forwardToParent(st);
                }

                case AgentEvent.ToolResultEvent tre -> {
                    toolCount++;
                    emitProgress(description, spec.name(), tre.toolName(), tre.output(),
                            tre.isError(), false, toolCount, elapsedSeconds(startNanos));
                    forwardToParent(tre);
                }

                case AgentEvent.UsageEvent ue -> {
                    totalInputTokens += ue.inputTokens();
                    totalOutputTokens += ue.outputTokens();
                    forwardToParent(ue);
                }

                case AgentEvent.ErrorEvent err -> {
                    forwardToParent(err);
                    emitProgress(description, spec.name(), null, null, true, true, toolCount, elapsedSeconds(startNanos));
                    return ToolResult.error("Agent failed: " + err.message());
                }

                case AgentEvent.LoopComplete lc -> {
                    double totalTime = elapsedSeconds(startNanos);
                    emitProgress(description, spec.name(), null, null, false, true, toolCount, totalTime);

                    String result = output.isEmpty() ? "(agent produced no output)" : output.toString();
                    long elapsedMs = Math.round(totalTime * 1000);

                    // Worktree cleanup: auto-remove if clean, preserve if dirty
                    String wtInfo = "";
                    if (wtResult != null) {
                        boolean hasChanges = WorktreeChanges.hasChanges(
                                wtResult.worktreePath(), wtResult.headCommit());
                        if (hasChanges) {
                            wtInfo = "\n\nWorktree kept at %s (branch %s) — has uncommitted changes or new commits."
                                    .formatted(wtResult.worktreePath(), wtResult.worktreeBranch());
                        } else {
                            AgentWorktree.remove(wtResult.worktreePath(),
                                    wtResult.worktreeBranch(), wtResult.gitRoot());
                        }
                    }

                    return ToolResult.success(
                            "Agent \"%s\" completed in %d.%03ds.%s\n\n%s".formatted(
                                    description, elapsedMs / 1000, elapsedMs % 1000, wtInfo, result));
                }

                default -> {
                    // ThinkingText, ThinkingComplete, ToolUseEvent, TurnComplete, etc.
                }
            }
        }
    }

    private ToolResult runAsync(SubAgentSpec spec, String description, String prompt, String modelOverride, String isolation) {
        if (taskManager == null) {
            return ToolResult.error("Background execution not available (no task manager configured)");
        }

        // Worktree isolation for async agents
        String agentWorkDir = workDir;
        String finalPrompt = prompt;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            try {
                byte[] rndBytes = new byte[4];
                new SecureRandom().nextBytes(rndBytes);
                String hex = HexFormat.of().formatHex(rndBytes);
                String slug = "agent-a" + hex.substring(0, 7);
                var wtResult = AgentWorktree.create(slug, worktreeManager.getProjectRoot(),
                        worktreeManager.getSymlinkDirs());
                agentWorkDir = wtResult.worktreePath();
                String parentCwd = workDir != null ? workDir : System.getProperty("user.dir");
                finalPrompt = AgentWorktree.buildNotice(parentCwd, wtResult.worktreePath())
                        + "\n\n" + prompt;
            } catch (Exception e) {
                return ToolResult.error("Error creating agent worktree: " + e.getMessage());
            }
        }

        LlmClient subClient = selectClient(spec.model(), modelOverride);
        String taskId = taskManager.spawnSubAgent(subClient, parentRegistry, protocol, spec, finalPrompt,
                null, hookEngine, agentWorkDir, contextWindow, maxOutput);
        return ToolResult.success(
                "Agent \"%s\" launched in background (task %s). You will be notified when it completes."
                        .formatted(description, taskId));
    }

    private ToolResult runFork(String description, String prompt, String modelOverride) {
        if (parentConversation == null) {
            return ToolResult.error("Error: fork requires parent conversation context");
        }
        if (taskManager == null) {
            return ToolResult.error("Error: fork requires task manager for background execution");
        }

        // Check for nested fork
        for (var msg : parentConversation.getMessages()) {
            if (msg.getContent() != null && msg.getContent().contains(FORK_BOILERPLATE_TAG)) {
                return ToolResult.error("Error: cannot fork from a forked agent. "
                        + "Use subagent_type to spawn a definition-based agent instead.");
            }
        }

        ConversationManager forkedConv = buildForkedConversation(parentConversation,
                FORK_BOILERPLATE + "\n\nYour task:\n" + prompt);

        LlmClient subClient = selectClient(null, modelOverride);
        // Fork always runs in background
        String taskId = taskManager.spawnSubAgent(subClient, parentRegistry, protocol,
                SubAgentSpec.GENERAL_PURPOSE,
                prompt, forkedConv, hookEngine, workDir, contextWindow, maxOutput);

        return ToolResult.success(
                "Forked agent \"%s\" launched in background (task %s). "
                        + "Results will arrive via task-notification."
                        .formatted(description, taskId));
    }

    private ToolResult runAsTeammate(String teamName, String description, String prompt,
                                      String modelOverride, String isolation) {
        if (teamManager == null) {
            return ToolResult.error("Error: Team system not initialized");
        }

        TeamManager.Team team = teamManager.getTeam(teamName);
        if (team == null) {
            return ToolResult.error("Error: team '" + teamName + "' not found. Use TeamCreate first.");
        }

        String memberName = description.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (memberName.length() > 30) {
            memberName = memberName.substring(0, 30);
        }

        // Deduplicate member name
        String original = memberName;
        int suffix = 1;
        while (team.hasMember(memberName)) {
            suffix++;
            memberName = original + "-" + suffix;
        }

        // Build filtered tool registry for the teammate
        SubAgentSpec spec = resolveSpec("general-purpose");
        ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec);
        // Add SendMessage tool so teammates can communicate
        subRegistry.register(new TeamTools.SendMessageTool(teamManager, memberName));

        String addendum = TeammateRunner.buildTeammateAddendum(
                teamName, memberName, team.memberNames());

        // Worktree isolation
        String agentWorkDir = workDir;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            try {
                byte[] rndBytes = new byte[4];
                new SecureRandom().nextBytes(rndBytes);
                String hex = HexFormat.of().formatHex(rndBytes);
                String slug = "agent-a" + hex.substring(0, 7);
                var wtResult = AgentWorktree.create(slug, worktreeManager.getProjectRoot(),
                        worktreeManager.getSymlinkDirs());
                agentWorkDir = wtResult.worktreePath();
            } catch (Exception e) {
                return ToolResult.error("Error creating agent worktree: " + e.getMessage());
            }
        }

        LlmClient subClient = selectClient(null, modelOverride);

        SpawnDispatcher.SpawnConfig config = new SpawnDispatcher.SpawnConfig(
                team, memberName, prompt, addendum,
                subClient, subRegistry, protocol,
                null, agentWorkDir);

        try {
            SpawnDispatcher.SpawnResult result = SpawnDispatcher.spawnTeammate(config);
            return ToolResult.success(
                    "Teammate \"" + memberName + "\" spawned in team \"" + teamName
                            + "\" (mode: " + result.mode() + "). "
                            + "Use SendMessage to communicate with them.");
        } catch (Exception e) {
            return ToolResult.error("Error spawning teammate: " + e.getMessage());
        }
    }

    // ---- Helpers ----

    static ConversationManager buildForkedConversation(ConversationManager parent, String taskUserMessage) {
        ConversationManager forked = new ConversationManager();
        for (var msg : parent.getMessages()) {
            boolean hasToolUses = msg.getToolUses() != null && !msg.getToolUses().isEmpty();
            boolean hasToolResults = msg.getToolResults() != null && !msg.getToolResults().isEmpty();

            if (hasToolUses && !hasToolResults) {
                // Pending tool_use — patch with placeholder results
                forked.addAssistantFull(msg.getContent(), msg.getThinkingBlocks(), msg.getToolUses(), null);
                var placeholders = msg.getToolUses().stream()
                        .map(tu -> new ToolResultBlock(
                                tu.toolUseId(), "(tool execution interrupted by fork)", false))
                        .toList();
                forked.addToolResultsMessage(placeholders);
            } else if (hasToolUses) {
                forked.addAssistantFull(msg.getContent(), msg.getThinkingBlocks(), msg.getToolUses(), null);
            } else if (hasToolResults) {
                forked.addToolResultsMessage(msg.getToolResults());
            } else if ("assistant".equals(msg.getRole())) {
                forked.addAssistantMessage(msg.getContent());
            } else if ("user".equals(msg.getRole())) {
                forked.addUserMessage(msg.getContent());
            }
            // system-reminders are skipped (they're injected dynamically by LiRuntime)
        }
        forked.addUserMessage(taskUserMessage);
        return forked;
    }

    private SubAgentSpec resolveSpec(String name) {
        if (agentSpecs != null) {
            SubAgentSpec spec = agentSpecs.get(name);
            if (spec != null) return spec;
        }
        return switch (name) {
            case "general-purpose" -> SubAgentSpec.GENERAL_PURPOSE;
            case "plan" -> SubAgentSpec.PLAN;
            case "explore" -> SubAgentSpec.EXPLORE;
            default -> null;
        };
    }

    private LlmClient selectClient(String specModel, String overrideModel) {
        String model = (overrideModel != null && !overrideModel.isEmpty()) ? overrideModel : specModel;
        if (model == null || model.isEmpty()) {
            return client;
        }
        if (modelResolver != null) {
            LlmClient resolved = modelResolver.resolve(model, buildSubAgentSystemPrompt());
            if (resolved != null) {
                return resolved;
            }
        }
        return client;
    }

    /**
     * Build a minimal system prompt for sub-agents. The role-specific
     * instructions are injected separately as system-reminders in the
     * sub-agent's ConversationManager.
     */
    public static String buildSubAgentSystemPromptStatic() {
        var opts = new PromptBuilder.BuildOptions(null, null, null, null);
        return PromptBuilder.buildSystemPrompt(opts);
    }

    private static String buildSubAgentSystemPrompt() {
        return buildSubAgentSystemPromptStatic();
    }

    private void forwardToParent(AgentEvent event) {
        if (parentQueue != null) {
            try {
                parentQueue.put(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void emitProgress(String description, String agentType,
                              String toolName, String toolOutput,
                              boolean isError, boolean done, int toolCount, double totalTime) {
        if (progressListener != null) {
            progressListener.accept(new SubAgentProgress(
                    agentType, description, toolName, toolOutput,
                    isError, done, toolCount, totalTime));
        }
    }

    static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static String getStringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }
}
