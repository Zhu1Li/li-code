package com.licode.runtime;

import com.licode.agent.Agent;
import com.licode.agent.AgentEvent;
import com.licode.agent.TestFailureDetector;
import com.licode.compact.ContextCompactor;
import com.licode.config.McpServerConfig;
import com.licode.config.ProviderConfig;
import com.licode.hook.HookConfig;
import com.licode.hook.HookEngine;
import com.licode.conversation.ConversationManager;
import com.licode.conversation.Message;
import com.licode.instructions.InstructionsLoader;
import com.licode.llm.LlmClient;
import com.licode.llm.StreamCallback;
import com.licode.mcp.McpManager;
import com.licode.memory.MemoryManager;
import com.licode.permission.PermissionChecker;
import com.licode.permission.PermissionMode;
import com.licode.prompt.PromptBuilder;
import com.licode.prompt.PromptSections;
import com.licode.session.SessionManager;
import com.licode.skill.LoadSkillTool;
import com.licode.skill.Skill;
import com.licode.skill.SkillCatalog;
import com.licode.skill.SkillExecutor;
import com.licode.skill.SkillHost;
import com.licode.skill.SkillMeta;
import com.licode.subagent.AgentLoader;
import com.licode.subagent.AgentTool;
import com.licode.subagent.ModelResolver;
import com.licode.subagent.SubAgentSpec;
import com.licode.subagent.SubAgentTaskManager;
import com.licode.subagent.ToolFilter;
import com.licode.tool.Tool;
import com.licode.tool.ToolRegistry;
import com.licode.tool.impl.EnterWorktreeTool;
import com.licode.tool.impl.ExitWorktreeTool;
import com.licode.team.TeamManager;
import com.licode.team.TeamTools;
import com.licode.team.TeammateRunner;
import com.licode.team.Coordinator;
import com.licode.task.TaskList;
import com.licode.task.TaskTools;
import com.licode.worktree.WorktreeManager;
import com.licode.worktree.WorktreeSessionStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class LiRuntime implements SkillHost {

    private final LlmClient llmClient;
    private final ProviderConfig config;
    private final ConversationManager conversation;
    private final ToolRegistry toolRegistry;
    private volatile Thread streamingThread;

    // Store last agent for /compact command access
    private volatile Agent lastAgent;

    // Instruction / memory / session management
    private String instructionsContent;
    private String instructionsFingerprint;
    private MemoryManager memoryManager;
    private com.licode.failure.FailureStore failureStore;
    private SessionManager sessionManager;
    private String currentSessionId;

    private boolean planOnlyMode;
    private String workDir;
    private PromptBuilder.EnvironmentContext environmentContext;
    private String promptFingerprint;
    private PermissionChecker permissionChecker;
    private McpManager mcpManager;
    private volatile String mcpInstructions;
    private volatile boolean mcpInstructionsOk;

    // Hook system
    private HookEngine hookEngine;
    private List<HookConfig> hookConfigs;

    // Skill system
    private SkillCatalog skillCatalog;
    private SkillExecutor skillExecutor;
    private final Set<String> activeSkillNames = new LinkedHashSet<>();
    private final Map<String, String> activeSkillBodies = new HashMap<>();
    private Predicate<String> toolFilter;

    // SubAgent system
    private SubAgentTaskManager taskManager;
    private ModelResolver modelResolver;
    private Map<String, SubAgentSpec> agentSpecs;
    private AgentTool agentTool;

    // Worktree system
    private WorktreeManager worktreeManager;
    private EnterWorktreeTool enterWorktreeTool;
    private ExitWorktreeTool exitWorktreeTool;
    private String originalWorkDir;

    // AgentTeam system
    private TeamManager teamManager;

    public LiRuntime(LlmClient llmClient, ProviderConfig config, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.config = config;
        this.conversation = new ConversationManager();
        this.toolRegistry = toolRegistry;
    }

    // ── Factory methods ──────────────────────────────────────────

    public static LiRuntime create(ProviderConfig cfg) {
        return create(cfg, ToolRegistry.createDefault());
    }

    public static LiRuntime create(ProviderConfig cfg, ToolRegistry toolRegistry) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        String instructions = InstructionsLoader.load(workDir);
        var env = PromptBuilder.detectEnvironment(cfg.getModel());
        String systemPrompt = buildDefaultSystemPrompt(cfg, instructions, env);
        return create(cfg, systemPrompt, toolRegistry);
    }

    public static LiRuntime create(ProviderConfig cfg, String systemPrompt) {
        return create(cfg, systemPrompt, ToolRegistry.createDefault());
    }

    public static LiRuntime create(ProviderConfig cfg, String systemPrompt, ToolRegistry toolRegistry) {
        Path workDir = Path.of(System.getProperty("user.dir"));

        // Load instructions and initialize memory/session managers
        String instructions = InstructionsLoader.load(workDir);
        var memoryManager = new MemoryManager(workDir);
        var sessionManager = new SessionManager(workDir);

        var client = LlmClient.create(cfg, systemPrompt);
        var runtime = new LiRuntime(client, cfg, toolRegistry);
        runtime.environmentContext = PromptBuilder.detectEnvironment(cfg.getModel());
        runtime.permissionChecker = new PermissionChecker(PermissionMode.DEFAULT, workDir);
        runtime.workDir = workDir.toString();
        runtime.instructionsContent = instructions;
        runtime.instructionsFingerprint = InstructionsLoader.fingerprint(workDir);
        runtime.memoryManager = memoryManager;
        runtime.sessionManager = sessionManager;
        runtime.failureStore = new com.licode.failure.FailureStore(workDir);

        // Initialize skill system
        runtime.initializeSkills(workDir);

        return runtime;
    }

    private static String buildDefaultSystemPrompt(ProviderConfig cfg, String instructions,
                                                    PromptBuilder.EnvironmentContext env) {
        String memorySection = null;
        var opts = new PromptBuilder.BuildOptions(
                instructions != null && !instructions.isEmpty() ? instructions : null,
                null,
                memorySection,
                env);
        var builder = new PromptBuilder();
        builder.computeFingerprint(cfg.getModel(),
                System.getProperty("user.dir"), opts);
        return PromptBuilder.buildSystemPrompt(opts);
    }

    // ── Accessors ────────────────────────────────────────────────

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public PromptBuilder.EnvironmentContext getEnvironmentContext() {
        return environmentContext;
    }

    public void setPlanOnlyMode(boolean planOnlyMode) {
        this.planOnlyMode = planOnlyMode;
    }

    public void exitPlanMode(PermissionMode previousMode) {
        this.planOnlyMode = false;
        if (permissionChecker != null) {
            permissionChecker.setMode(previousMode);
        }
        conversation.addSystemReminder(
                com.licode.prompt.PlanModePrompt.PLAN_MODE_EXIT_REMINDER);
        if (com.licode.plan.PlanFile.planExists()) {
            conversation.addSystemReminder(
                    com.licode.prompt.PlanModePrompt.PLAN_MODE_REENTRY_REMINDER);
        }
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
        // Recreate PermissionChecker with updated project root
        if (workDir != null && !workDir.isEmpty()) {
            Path path = Path.of(workDir);
            this.permissionChecker = new PermissionChecker(
                    permissionChecker != null ? permissionChecker.getMode() : PermissionMode.DEFAULT,
                    path);
            // Reload instructions and memory/session for new workDir
            this.instructionsContent = InstructionsLoader.load(path);
            this.instructionsFingerprint = InstructionsLoader.fingerprint(path);
            this.memoryManager = new MemoryManager(path);
            this.failureStore = new com.licode.failure.FailureStore(path);
            this.sessionManager = new SessionManager(path);
        }
    }

    public PermissionChecker getPermissionChecker() {
        return permissionChecker;
    }

    public void setPermissionMode(PermissionMode mode) {
        if (permissionChecker != null) {
            permissionChecker.setMode(mode);
        }
    }

    public void setPlanFilePath(String path) {
        if (permissionChecker != null) {
            permissionChecker.setPlanFilePath(path);
        }
    }

    // Hook system accessors
    public HookEngine getHookEngine() {
        return hookEngine;
    }

    public com.licode.mcp.McpManager getMcpManager() {
        return mcpManager;
    }

    public void setHookConfigs(List<HookConfig> configs) {
        this.hookConfigs = configs;
        if (configs != null && !configs.isEmpty()) {
            this.hookEngine = new HookEngine();
            try {
                var hooks = HookConfig.toHooks(configs);
                this.hookEngine.loadHooks(hooks);
                System.err.println("[LiCode] Hooks loaded: " + hooks.size() + " hook(s)");
            } catch (HookEngine.HookValidationException e) {
                System.err.println("[LiCode] Hook validation failed, hooks disabled: " + e.getMessage());
                this.hookEngine = null;
            }
        }
    }

    private void initHookEngine() {
        if (hookConfigs != null && !hookConfigs.isEmpty() && hookEngine == null) {
            hookEngine = new HookEngine();
            try {
                hookEngine.loadHooks(HookConfig.toHooks(hookConfigs));
            } catch (HookEngine.HookValidationException e) {
                System.err.println("[LiCode] Hook validation failed, hooks disabled: " + e.getMessage());
                hookEngine = null;
            }
        }
    }

    // ── Skill system ─────────────────────────────────────────

    public SkillCatalog getSkillCatalog() {
        return skillCatalog;
    }

    private void initializeSkills(Path workDir) {
        skillCatalog = new SkillCatalog();
        skillCatalog.loadCatalog(workDir);
        skillExecutor = new SkillExecutor(skillCatalog);
        wireSkillsToAgent();
        registerLoadSkillTool();
        registerSaveMemoryTool();
        registerFailureTools();
    }

    private void wireSkillsToAgent() {
        // Skills are wired via wireSkillsToCommands(CommandRegistry)
        // called from LiCodeModel after runtime init.
    }

    public void wireSkillsToCommands(com.licode.command.CommandRegistry cmdRegistry) {
        if (skillCatalog == null || cmdRegistry == null) return;
        for (var meta : skillCatalog.list()) {
            String name = meta.name();
            String desc = meta.description();
            if (desc == null || desc.isBlank()) desc = name;
            boolean fork = meta.isFork();
            cmdRegistry.registerSkillCommand(name, desc, () -> {
                var skill = skillCatalog.getFull(name);
                return skill.map(Skill::promptBody).orElse(null);
            }, fork);
        }
        registerSkillManageCommand(cmdRegistry);
        registerTaskCommands(cmdRegistry);
        registerWorktreeCommands(cmdRegistry);
    }

    private void registerWorktreeCommands(com.licode.command.CommandRegistry cmdRegistry) {
        var cmd = new com.licode.command.Command("worktree",
                "Manage git worktrees (list | status | enter <name> | exit [keep|remove] [--discard])",
                java.util.List.of("wt"), com.licode.command.Command.CommandType.LOCAL, false);
        cmdRegistry.register(cmd, ctx -> {
            String args = ctx.args() != null ? ctx.args().trim() : "";
            String sub;
            String subArgs;
            int spaceIdx = args.indexOf(' ');
            if (spaceIdx > 0) {
                sub = args.substring(0, spaceIdx);
                subArgs = args.substring(spaceIdx + 1).trim();
            } else {
                sub = args.isEmpty() ? "list" : args;
                subArgs = "";
            }

            return switch (sub) {
                case "list" -> {
                    if (worktreeManager == null) yield "Worktree system not initialized.";
                    var list = worktreeManager.list();
                    if (list.isEmpty()) yield "No worktrees.";
                    var sb = new StringBuilder();
                    for (var wt : list) {
                        sb.append(wt.branch()).append(" → ").append(wt.path()).append("\n");
                    }
                    yield sb.toString().stripTrailing();
                }
                case "status" -> {
                    var session = WorktreeSessionStore.getCurrentSession();
                    if (session == null) yield "Not in a worktree session.";
                    yield "Worktree: " + session.worktreeName() + "\n"
                            + "Path: " + session.worktreePath() + "\n"
                            + "Branch: " + session.worktreeBranch() + "\n"
                            + "Original: " + session.originalCwd();
                }
                case "enter" -> {
                    if (enterWorktreeTool == null) yield "Worktree system not initialized.";
                    var result = enterWorktreeTool.execute(
                            subArgs.isEmpty() ? java.util.Map.of() : java.util.Map.of("name", subArgs));
                    yield result.isError() ? "Error: " + result.output() : result.output();
                }
                case "exit" -> {
                    if (exitWorktreeTool == null) yield "Worktree system not initialized.";
                    String action = "keep";
                    boolean discard = false;
                    if (!subArgs.isEmpty()) {
                        var parts = subArgs.split("\\s+");
                        if (parts.length > 0 && !parts[0].isEmpty()) action = parts[0];
                        for (String p : parts) {
                            if ("--discard".equals(p)) discard = true;
                        }
                    }
                    var exitArgs = new java.util.LinkedHashMap<String, Object>();
                    exitArgs.put("action", action);
                    if (discard) exitArgs.put("discard_changes", true);
                    var result = exitWorktreeTool.execute(exitArgs);
                    yield result.isError() ? "Error: " + result.output() : result.output();
                }
                default -> "Usage: /worktree [list|status|enter <name>|exit [keep|remove] [--discard]]";
            };
        });
    }

    private void registerTaskCommands(com.licode.command.CommandRegistry cmdRegistry) {
        var cmd = new com.licode.command.Command("tasks",
                "Manage background tasks (list | detail <id> | cancel <id>)",
                java.util.List.of(), com.licode.command.Command.CommandType.LOCAL, false);
        cmdRegistry.register(cmd, ctx -> {
            String args = ctx.args() != null ? ctx.args().trim() : "";
            String sub = "";
            String subArgs = "";
            int spaceIdx = args.indexOf(' ');
            if (spaceIdx > 0) {
                sub = args.substring(0, spaceIdx);
                subArgs = args.substring(spaceIdx + 1).trim();
            } else {
                sub = args;
            }

            if (taskManager == null) {
                return "Task manager is not initialized. SubAgent system may not be running.";
            }

            if (sub.isEmpty() || sub.equals("list")) {
                var tasks = taskManager.listTasks();
                if (tasks.isEmpty()) return "No background tasks.";
                var sb = new StringBuilder();
                for (var t : tasks) {
                    sb.append(t.id()).append(" [").append(t.status()).append("] ");
                    sb.append(t.name());
                    if (t.elapsedMs() > 0) sb.append(" (").append(formatElapsed(t.elapsedMs())).append(")");
                    sb.append("\n");
                }
                return sb.toString().stripTrailing();
            }
            if (sub.equals("detail") || sub.equals("info")) {
                if (subArgs.isEmpty()) return "Usage: /tasks detail <task_id>";
                var task = taskManager.getTask(subArgs);
                if (task == null) return "Task not found: " + subArgs;
                var sb = new StringBuilder();
                sb.append("Task: ").append(task.id()).append("\n");
                sb.append("Name: ").append(task.name()).append("\n");
                sb.append("Status: ").append(task.status()).append("\n");
                if (task.elapsedMs() > 0) sb.append("Time: ").append(formatElapsed(task.elapsedMs())).append("\n");
                if (task.inputTokens() > 0 || task.outputTokens() > 0) {
                    sb.append("Tokens: ").append(task.inputTokens()).append(" in, ")
                            .append(task.outputTokens()).append(" out\n");
                }
                if (task.error() != null && !task.error().isEmpty()) {
                    sb.append("Error: ").append(task.error()).append("\n");
                }
                if (task.output() != null && !task.output().isEmpty()) {
                    sb.append("Output:\n").append(task.output());
                }
                return sb.toString();
            }
            if (sub.equals("cancel")) {
                if (subArgs.isEmpty()) return "Usage: /tasks cancel <task_id>";
                var task = taskManager.getTask(subArgs);
                if (task == null) return "Task not found: " + subArgs;
                if (task.status() != SubAgentTaskManager.TaskStatus.RUNNING
                        && task.status() != SubAgentTaskManager.TaskStatus.PENDING) {
                    return "Cannot cancel task " + subArgs + " (status: " + task.status() + ")";
                }
                taskManager.cancelTask(subArgs);
                return "Cancelled task " + subArgs + " (" + task.name() + ")";
            }
            return "Usage: /tasks list | /tasks detail <id> | /tasks cancel <id>";
        });
    }

    private void registerSkillManageCommand(com.licode.command.CommandRegistry cmdRegistry) {
        var cmd = new com.licode.command.Command("skill",
                "Manage skills (list | info <name> | reload)",
                java.util.List.of(), com.licode.command.Command.CommandType.LOCAL, false);
        cmdRegistry.register(cmd, ctx -> {
            String args = ctx.args() != null ? ctx.args().trim() : "";
            String sub = "";
            String subArgs = "";
            int spaceIdx = args.indexOf(' ');
            if (spaceIdx > 0) {
                sub = args.substring(0, spaceIdx);
                subArgs = args.substring(spaceIdx + 1).trim();
            } else {
                sub = args;
            }

            if (sub.isEmpty() || sub.equals("list")) {
                var metaList = skillCatalog.list();
                if (metaList.isEmpty()) return "No skills installed.";
                var sb = new StringBuilder();
                for (var m : metaList) {
                    sb.append(m.name());
                    sb.append("  ".repeat(Math.max(1, 3 - m.name().length() / 8)));
                    sb.append(m.description());
                    sb.append("  [").append(skillCatalog.source(m.name())).append("]\n");
                }
                return sb.toString().stripTrailing();
            }
            if (sub.equals("info")) {
                if (subArgs.isEmpty()) return "Usage: /skill info <name>";
                var skill = skillCatalog.get(subArgs);
                if (skill.isEmpty()) return "Skill not found: " + subArgs;
                var s = skill.get();
                var sb = new StringBuilder();
                sb.append("Skill: ").append(s.meta().name()).append('\n');
                sb.append("Description: ").append(s.meta().description()).append('\n');
                sb.append("Mode: ").append(s.meta().mode()).append('\n');
                sb.append("Model: ").append(s.meta().model().isEmpty() ? "(default)" : s.meta().model()).append('\n');
                sb.append("ForkContext: ").append(s.meta().forkContext()).append('\n');
                sb.append("AllowedTools: ").append(s.meta().allowedTools().isEmpty()
                        ? "(all)" : String.join(", ", s.meta().allowedTools())).append('\n');
                sb.append("Source: ").append(skillCatalog.source(s.meta().name())).append('\n');
                sb.append("Directory: ").append(s.isDirectory() ? "yes" : "no").append('\n');
                if (s.sourceDir() != null) sb.append("Path: ").append(s.sourceDir());
                else sb.append("Path: (builtin)");
                return sb.toString();
            }
            if (sub.equals("reload")) {
                skillCatalog.reload(Path.of(workDir));
                wireSkillsToCommands(cmdRegistry);
                return "Reloaded " + skillCatalog.list().size() + " skills.";
            }
            return "Usage: /skill list | /skill info <name> | /skill reload";
        });
    }

    private void registerLoadSkillTool() {
        if (toolRegistry == null || skillCatalog == null) return;
        var loadSkill = new LoadSkillTool(skillCatalog, skillExecutor, this);
        toolRegistry.register(loadSkill);
    }

    /** 注册通用的"写记忆"工具，让 Agent 能把持久事实（如风格 profile）落盘到长期记忆。 */
    private void registerSaveMemoryTool() {
        if (toolRegistry == null || memoryManager == null) return;
        toolRegistry.register(new com.licode.tool.impl.SaveMemoryTool(memoryManager));
    }

    /** 注册失败记忆的读写工具：RecordFailure（沉淀）/ RecallFailures（查历史）。 */
    private void registerFailureTools() {
        if (toolRegistry == null || failureStore == null) return;
        toolRegistry.register(new com.licode.tool.impl.RecordFailureTool(failureStore));
        toolRegistry.register(new com.licode.tool.impl.RecallFailuresTool(failureStore));
    }

    // ── SkillHost implementation ────────────────────────────────────

    @Override
    public void activateSkill(String name, String body) {
        activeSkillNames.add(name);
        activeSkillBodies.put(name, body);
        // Record for post-compaction recovery attachment so the model
        // remembers which skills are active after Layer 2 compaction.
        Agent current = lastAgent;
        if (current != null) {
            var rs = current.getRecoveryState();
            if (rs != null) {
                rs.recordSkillInvocation(name, body);
            }
        }
    }

    @Override
    public void deactivateSkill(String name) {
        activeSkillNames.remove(name);
        activeSkillBodies.remove(name);
        computeToolFilter();
    }

    @Override
    public void clearActiveSkills() {
        activeSkillNames.clear();
        activeSkillBodies.clear();
        toolFilter = null;
    }

    @Override
    public Set<String> getActiveSkillNames() {
        return Set.copyOf(activeSkillNames);
    }

    @Override
    public String buildActiveSkillsContext() {
        if (activeSkillNames.isEmpty() || skillCatalog == null) return "";
        return skillCatalog.buildActiveContext(activeSkillNames);
    }

    @Override
    public void setToolFilter(Predicate<String> filter) {
        this.toolFilter = filter;
    }

    @Override
    public com.licode.tool.ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    private void computeToolFilter() {
        if (activeSkillNames.isEmpty() || skillCatalog == null) {
            toolFilter = null;
            return;
        }
        Set<String> union = new LinkedHashSet<>();
        for (String name : activeSkillNames) {
            var skill = skillCatalog.get(name);
            if (skill.isPresent() && !skill.get().meta().allowedTools().isEmpty()) {
                union.addAll(skill.get().meta().allowedTools());
            }
        }
        if (union.isEmpty()) {
            toolFilter = null;
        } else {
            // Always allow LoadSkill (system tool)
            union.add("LoadSkill");
            toolFilter = union::contains;
        }
    }

    // ── SubAgent system ──────────────────────────────────────

    /**
     * Initialize the sub-agent system. Must be called after construction,
     * before the first {@link #ask} call. Idempotent: subsequent calls update
     * the model resolver with the latest provider list.
     *
     * @param workDir      current working directory
     * @param allProviders all configured providers for model selection
     */
    public void initSubAgentSystem(Path workDir, List<ProviderConfig> allProviders) {
        this.modelResolver = ModelResolver.create(allProviders, config, llmClient);
        this.agentSpecs = AgentLoader.loadAll(workDir);
        this.taskManager = new SubAgentTaskManager();

        // Initialize worktree manager
        this.originalWorkDir = workDir.toString();
        if (this.worktreeManager == null) {
            this.worktreeManager = new WorktreeManager(workDir.toString(), List.of(), 24);
        }

        // Create and register worktree tools
        this.enterWorktreeTool = new EnterWorktreeTool(worktreeManager,
                this::setWorkDir, "wt");
        this.exitWorktreeTool = new ExitWorktreeTool(worktreeManager,
                () -> setWorkDir(originalWorkDir));
        toolRegistry.register(enterWorktreeTool);
        toolRegistry.register(exitWorktreeTool);

        // Create AgentTool and wire dependencies
        this.agentTool = new AgentTool(llmClient, toolRegistry, config.getProtocol());
        this.agentTool.setModelResolver(modelResolver);
        this.agentTool.setAgentSpecs(agentSpecs);
        this.agentTool.setTaskManager(taskManager);
        this.agentTool.setWorkDir(workDir.toString());
        this.agentTool.setWorktreeManager(worktreeManager);
        if (hookEngine != null) this.agentTool.setHookEngine(hookEngine);
        this.agentTool.setContextWindow(config.resolvedContextWindow());
        this.agentTool.setMaxOutput(config.resolvedMaxOutputTokens());

        // Register AgentTool (idempotent: ToolRegistry.put overwrites by name)
        toolRegistry.register(agentTool);

        // Restore worktree session from disk
        var saved = WorktreeSessionStore.load(workDir.toString());
        if (saved != null && java.nio.file.Files.exists(Path.of(saved.worktreePath()))) {
            WorktreeSessionStore.restoreSession(saved);
        }

        // Register slash commands for task management
        // (deferred until wireSkillsToCommands is called)

        // Initialize TeamManager
        this.teamManager = new TeamManager(Path.of(workDir.toString(), ".licode", "teams"));
        this.agentTool.setTeamManager(teamManager);

        // Register team tools
        toolRegistry.register(new TeamTools.TeamCreateTool(teamManager));
        toolRegistry.register(new TeamTools.TeamDeleteTool(teamManager));
        toolRegistry.register(new TeamTools.SendMessageTool(teamManager, "lead"));

        // Register task tools
        var taskList = new TaskList("default", workDir);
        toolRegistry.register(new TaskTools.TaskCreateTool(taskList));
        toolRegistry.register(new TaskTools.TaskGetTool(taskList));
        toolRegistry.register(new TaskTools.TaskListTool(taskList));
        toolRegistry.register(new TaskTools.TaskUpdateTool(taskList));
    }

    // Worktree accessors
    public WorktreeManager getWorktreeManager() {
        return worktreeManager;
    }

    // Team accessor
    public TeamManager getTeamManager() {
        return teamManager;
    }

    // Drain external notifications (team mailbox + subagent tasks)
    public List<String> drainExternalNotifications() {
        var notes = new java.util.ArrayList<String>();
        if (teamManager != null) {
            notes.addAll(TeammateRunner.drainLeadMailbox(teamManager));
        }
        if (taskManager != null) {
            var taskNotes = taskManager.drainNotifications();
            if (!taskNotes.isEmpty()) {
                taskNotes.stream()
                        .map(LiRuntime::formatTaskNotification)
                        .forEach(notes::add);
            }
        }
        return notes;
    }

    public void startStaleCleanup(java.util.concurrent.ScheduledExecutorService executor,
                                  int intervalSeconds, int cutoffHours) {
        com.licode.worktree.StaleCleanup.startCleanupLoop(
                executor, workDir, intervalSeconds, cutoffHours);
    }

    public void shutdownWorktree() {
        if (worktreeManager != null) {
            // Session already persisted; just null out
            WorktreeSessionStore.restoreSession(null);
        }
    }

    static String formatTaskNotification(SubAgentTaskManager.TaskNotification note) {
        String statusIcon = switch (note.status()) {
            case COMPLETED -> "[DONE]";
            case FAILED -> "[FAILED]";
            case CANCELLED -> "[CANCELLED]";
            default -> "[UNKNOWN]";
        };
        var sb = new StringBuilder();
        sb.append(statusIcon).append(" ").append(note.name()).append(" (").append(note.taskId()).append(")");
        if (note.elapsedMs() > 0) {
            sb.append(" in ").append(formatElapsed(note.elapsedMs()));
        }
        if (note.inputTokens() > 0 || note.outputTokens() > 0) {
            sb.append(" | tokens: ").append(note.inputTokens()).append(" in, ").append(note.outputTokens()).append(" out");
        }
        if (!note.output().isEmpty()) {
            String summary = note.output();
            if (summary.length() > 200) summary = summary.substring(0, 200) + "...";
            sb.append("\n").append(summary);
        }
        return sb.toString();
    }

    private static String formatElapsed(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        long min = ms / 60_000;
        long sec = (ms % 60_000) / 1000;
        return min + "m" + sec + "s";
    }

    public boolean isStale() {
        return new PromptBuilder().isStale(config.getModel(),
                System.getProperty("user.dir"),
                new PromptBuilder.BuildOptions(instructionsContent, null, null, environmentContext));
    }

    // ── Fork skill execution ─────────────────────────────────────

    /**
     * 运行一个 fork skill：派一个**隔离子 Agent** 执行 skill 体——独立对话、独立 turn 预算、
     * BYPASS 权限、**共享工作区**（修复/改动直接落真实代码树），进度经 {@code callback} 实时
     * 流回 UI。与主对话完全隔离：不写主 session、不触发主记忆抽取、不污染主 conversation。
     * 这是 fork skill（如 /commit、/fix-tests）真正"派子 Agent"的入口。
     */
    public void askForkSkill(String skillName, String args, StreamCallback callback) {
        cancel(); // stop any previous streaming thread
        if (skillCatalog == null || toolRegistry == null) {
            callback.onError("Skill system not initialized");
            return;
        }
        var skillOpt = skillCatalog.getFull(skillName);
        if (skillOpt.isEmpty()) {
            callback.onError("Skill not found: " + skillName);
            return;
        }
        var skill = skillOpt.get();
        String body = skill.render(args);
        List<String> allowedTools = skill.meta().allowedTools();
        String model = skill.meta().model();

        initHookEngine();

        // 构造隔离子 Agent（工具按 skill 白名单过滤、BYPASS、共享 workDir）。
        SubAgentSpec spec = new SubAgentSpec(
                "skill-fork:" + skillName, "Fork skill: " + skillName,
                allowedTools != null ? allowedTools : List.of(),
                List.of(), body, 0, model != null ? model : "");
        ToolRegistry subRegistry = ToolFilter.filterForAgent(toolRegistry, spec, false);
        String systemPrompt = AgentTool.buildSubAgentSystemPromptStatic();
        LlmClient subClient = modelResolver != null ? modelResolver.resolve(model, systemPrompt) : llmClient;
        if (subClient == null) subClient = llmClient;

        Path cwd = workDir != null ? Path.of(workDir) : Path.of(System.getProperty("user.dir"));
        Agent subAgent = new Agent(subClient, subRegistry, config.getProtocol());
        subAgent.setMaxIterations(200);
        subAgent.setPermissionChecker(new PermissionChecker(PermissionMode.BYPASS, cwd));
        if (hookEngine != null) subAgent.setHookEngine(hookEngine);
        subAgent.setContextWindow(config.resolvedContextWindow());
        subAgent.setMaxOutput(config.resolvedMaxOutputTokens());
        if (workDir != null) subAgent.setWorkDir(workDir);
        lastAgent = subAgent; // 让 Stop/cancel 作用到 fork 子 Agent

        ConversationManager forkConv = new ConversationManager();
        forkConv.addSystemReminder(body);
        forkConv.addUserMessage((args != null && !args.isBlank())
                ? args : "Execute the skill instructions above now.");

        BlockingQueue<AgentEvent> queue = subAgent.run(forkConv);

        streamingThread = Thread.startVirtualThread(() -> {
            try {
                var seenToolCalls = new HashSet<String>();
                while (!Thread.currentThread().isInterrupted()) {
                    AgentEvent event = queue.poll(30, TimeUnit.SECONDS);
                    if (event == null) {
                        if (subAgent.isAlive()) continue;
                        callback.onError("Fork skill stopped unexpectedly");
                        return;
                    }
                    switch (event) {
                        case AgentEvent.StreamText st -> callback.onTextDelta(st.text());
                        case AgentEvent.ThinkingText tt -> callback.onThinkingDelta(tt.text());
                        case AgentEvent.ThinkingComplete tc ->
                                callback.onThinkingComplete(tc.thinking(), tc.signature());
                        case AgentEvent.ToolUseEvent tue -> {
                            if (seenToolCalls.add(tue.toolId())) {
                                callback.onToolCallStart(tue.toolId(), tue.toolName());
                            } else {
                                callback.onToolCallComplete(tue.toolId(), tue.toolName(), tue.args());
                            }
                        }
                        case AgentEvent.ToolCallDelta tcd ->
                                callback.onToolCallDelta(tcd.toolId(), tcd.argumentsDelta());
                        case AgentEvent.ToolResultEvent tre -> {
                            callback.onToolCallComplete(tre.toolId(), tre.toolName(), Map.of());
                            callback.onToolResult(tre.toolId(), tre.toolName(),
                                    tre.output(), tre.isError(), tre.elapsedSeconds());
                        }
                        case AgentEvent.PermissionRequestEvent preq ->
                                callback.onPermissionRequest(preq.toolId(), preq.toolName(),
                                        preq.description(), preq.future());
                        case AgentEvent.LoopComplete lc -> {
                            // 隔离：不落主 session、不触发主记忆抽取。
                            callback.onComplete(lc.stopReason(), lc.totalInputTokens(), lc.totalOutputTokens());
                            return;
                        }
                        case AgentEvent.ErrorEvent ee -> {
                            callback.onError(ee.message());
                            return;
                        }
                        default -> {}
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.onError("Fork skill interrupted");
            }
        });
    }

    // ── Main ask ─────────────────────────────────────────────────

    public void ask(String userMessage, StreamCallback callback) {
        ask(userMessage, callback, false);
    }

    public void ask(String userMessage, StreamCallback callback, boolean bypass) {
        cancel(); // stop any previous streaming thread

        // Initialize hook engine if not yet done
        initHookEngine();

        // Inject system-reminders on first turn (before user message)
        // Environment context lives in system prompt now, not as a message
        boolean isFirstTurn = conversation.getMessages().isEmpty();
        if (isFirstTurn) {
            // LICODE.md instructions
            if (instructionsContent != null && !instructionsContent.isEmpty()) {
                conversation.addSystemReminder(
                        "# claudeMd\n\n" + instructionsContent);
            }
            // Auto memories
            if (memoryManager != null) {
                memoryManager.injectMemories(conversation);
            }
            // MCP server instructions (once, after async connection completes)
            if (mcpInstructions != null && !mcpInstructions.isEmpty() && !mcpInstructionsOk) {
                conversation.addSystemReminder(mcpInstructions);
                mcpInstructionsOk = true;
            }
        } else {
            // Check if instructions have changed (file was edited)
            if (workDir != null) {
                String newFingerprint = InstructionsLoader.fingerprint(Path.of(workDir));
                if (!newFingerprint.equals(instructionsFingerprint)) {
                    instructionsContent = InstructionsLoader.load(Path.of(workDir));
                    instructionsFingerprint = newFingerprint;
                    replaceInstructionsInConversation();
                }
            }
        }

        // Create session if needed
        if (currentSessionId == null && sessionManager != null) {
            currentSessionId = sessionManager.createSession();
        }

        // Dynamic context: MCP servers, deferred tool names (per-turn, after history)
        String dynamicCtx = buildDynamicContext();
        if (dynamicCtx != null && !dynamicCtx.isEmpty()) {
            conversation.addSystemReminder(dynamicCtx);
        }

        // Inject active skills context (per-turn, pinned in env context)
        if (!activeSkillNames.isEmpty()) {
            String activeCtx = skillCatalog.buildActiveContext(activeSkillNames);
            if (activeCtx != null && !activeCtx.isEmpty()) {
                conversation.addSystemReminder(activeCtx);
            }
        }

        if (userMessage != null) {
            conversation.addUserMessage(userMessage);
        }
        conversation.trimToWindow(config.resolvedContextWindow());

        Agent agent = new Agent(llmClient, toolRegistry, config.getProtocol());
        agent.setPlanOnlyMode(planOnlyMode);
        if (workDir != null) agent.setWorkDir(workDir);
        agent.setPermissionChecker(permissionChecker);
        agent.setContextWindow(config.resolvedContextWindow());
        agent.setMaxOutput(config.resolvedMaxOutputTokens());
        agent.setBypass(bypass);
        // Apply skill tool filter to Agent
        if (toolFilter != null) {
            agent.setToolFilter(t -> !t.isSystemTool() ? toolFilter.test(t.name()) : true);
        }
        // Coordinator mode tool filter (applied after skill filter)
        if (teamManager != null && !teamManager.listTeams().isEmpty()
                && Coordinator.isCoordinatorEnabled()) {
            final var existingFilter = agent.getToolFilter();
            if (existingFilter != null) {
                agent.setToolFilter(t -> existingFilter.test(t)
                        && Coordinator.isCoordinatorTool(t.name()));
            } else {
                agent.setToolFilter(t -> !t.isSystemTool()
                        && Coordinator.isCoordinatorTool(t.name()));
            }
        }
        // Wire hook engine to agent
        if (hookEngine != null) {
            agent.setHookEngine(hookEngine);
        }
        // Wire sub-agent + team notification supplier
        if (taskManager != null || teamManager != null) {
            final var tmgr = teamManager;
            final var tskmgr = taskManager;
            agent.setNotificationSupplier(() -> {
                var notes = new java.util.ArrayList<String>();
                if (tmgr != null) {
                    notes.addAll(TeammateRunner.drainLeadMailbox(tmgr));
                }
                if (tskmgr != null) {
                    var taskNotes = tskmgr.drainNotifications();
                    if (!taskNotes.isEmpty()) {
                        taskNotes.stream()
                                .map(LiRuntime::formatTaskNotification)
                                .forEach(notes::add);
                    }
                }
                return notes;
            });
            // Update AgentTool's parent conversation reference
            if (agentTool != null) {
                agentTool.setParentConversation(conversation);
                if (hookEngine != null) agentTool.setHookEngine(hookEngine);
            }
        }
        lastAgent = agent;

        // PRE_SEND hook (before streaming to LLM)
        if (hookEngine != null) {
            hookEngine.runHooks(new HookEngine.HookContext(
                    HookEngine.EventName.PRE_SEND, null, null, null, userMessage, null));
        }

        BlockingQueue<AgentEvent> agentQueue = agent.run(conversation);

        streamingThread = Thread.startVirtualThread(() -> {
            try {
                var seenToolCalls = new HashSet<String>();
                boolean testFailureHinted = false;
                while (!Thread.currentThread().isInterrupted()) {
                    AgentEvent event = agentQueue.poll(30, TimeUnit.SECONDS);
                    if (event == null) {
                        // A long-running tool (Bash up to 600s, sub-agents, teams) emits no
                        // events while it works. As long as the agent thread is alive, keep
                        // waiting rather than declaring a false timeout. Only error out if the
                        // worker actually died without delivering a terminal event.
                        if (agent.isAlive()) continue;
                        callback.onError("Agent stopped unexpectedly");
                        saveRecentMessages();
                        return;
                    }
                    switch (event) {
                        case AgentEvent.StreamText st -> callback.onTextDelta(st.text());
                        case AgentEvent.ThinkingText tt -> callback.onThinkingDelta(tt.text());
                        case AgentEvent.ThinkingComplete tc ->
                                callback.onThinkingComplete(tc.thinking(), tc.signature());
                        case AgentEvent.ToolUseEvent tue -> {
                            if (seenToolCalls.add(tue.toolId())) {
                                callback.onToolCallStart(tue.toolId(), tue.toolName());
                            } else {
                                callback.onToolCallComplete(tue.toolId(), tue.toolName(), tue.args());
                            }
                        }
                        case AgentEvent.ToolCallDelta tcd ->
                                callback.onToolCallDelta(tcd.toolId(), tcd.argumentsDelta());
                        case AgentEvent.ToolResultEvent tre -> {
                            callback.onToolCallComplete(tre.toolId(), tre.toolName(), Map.of());
                            callback.onToolResult(tre.toolId(), tre.toolName(),
                                    tre.output(), tre.isError(), tre.elapsedSeconds());
                            // 测试失败 → 提示用户可以 /fix-tests（每轮至多一次，纯 UI、不进模型上下文）
                            if (!testFailureHinted && "Bash".equalsIgnoreCase(tre.toolName())
                                    && TestFailureDetector.looksLikeTestFailure(tre.output())) {
                                testFailureHinted = true;
                                callback.onNotice(TestFailureDetector.hint());
                            }
                        }
                        case AgentEvent.PermissionRequestEvent preq ->
                                callback.onPermissionRequest(preq.toolId(), preq.toolName(),
                                        preq.description(), preq.future());
                        case AgentEvent.LoopComplete lc -> {
                            // TURN_END hook
                            if (hookEngine != null) {
                                hookEngine.runHooks(new HookEngine.HookContext(
                                        HookEngine.EventName.TURN_END, null, null, null, null, null));
                            }
                            callback.onComplete(lc.stopReason(), lc.totalInputTokens(), lc.totalOutputTokens());
                            // Save messages to session
                            saveRecentMessages();
                            // Trigger memory extraction
                            triggerMemoryExtraction();
                            return;
                        }
                        case AgentEvent.ErrorEvent ee -> {
                            // ERROR hook
                            if (hookEngine != null) {
                                hookEngine.runHooks(new HookEngine.HookContext(
                                        HookEngine.EventName.ERROR, null, null, null, null, ee.message()));
                            }
                            callback.onError(ee.message());
                            // Persist whatever was produced so the transcript isn't lost on error.
                            saveRecentMessages();
                            return;
                        }
                        default -> {}
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.onError("Stream interrupted");
                saveRecentMessages();
            }
        });
    }

    public void cancel() {
        Thread t = streamingThread;
        if (t != null) {
            t.interrupt();
            streamingThread = null;
        }
        if (lastAgent != null) {
            lastAgent.cancel();
        }
    }

    // ── MCP ──────────────────────────────────────────────────────

    public void connectMcpServers(List<McpServerConfig> serverConfigs,
                                  java.util.function.Consumer<String> statusCallback) {
        if (serverConfigs == null || serverConfigs.isEmpty()) return;
//        System.err.println("[LiCode MCP] Initializing " + serverConfigs.size() + " MCP server(s)...");
        mcpManager = new McpManager(serverConfigs);
        Thread.startVirtualThread(() -> {
            try {
                if (statusCallback != null) {
                    statusCallback.accept("connecting");
                }
                var result = mcpManager.connectAll();
                for (var tool : result.tools()) {
                    toolRegistry.register(tool);
                }
                int toolCount = toolRegistry.getDeferredTools().size();
//                System.err.println("[LiCode MCP] Connected successfully. "
//                        + toolCount + " MCP tool(s) registered.");
                if (statusCallback != null) {
                    int serverCount = serverConfigs.size();
                    statusCallback.accept("connected:" + serverCount + ":" + toolCount);
                }
                // Build MCP server instructions (injected once on first turn)
                mcpInstructions = buildMcpInstructions(result);
                // MCP errors are displayed in TUI status bar — no stderr during TUI
            } catch (Exception e) {
                // MCP fatal error is displayed in TUI status bar — no stderr during TUI
                if (statusCallback != null) {
                    statusCallback.accept("error:" + e.getMessage());
                }
            }
        });
    }

    public void shutdownMcp() {
        if (mcpManager != null) {
            mcpManager.shutdown();
        }
    }

    // ── Dynamic context ─────────────────────────────────────────────

    // Build MCP server instructions block (injected once on first turn),
    // matching mewCode Python's _mcp_instructions format:
    //   # MCP Server Instructions
    //   The following MCP servers are connected...
    //   ## server1
    //   Available tools: mcp__server1__tool1, mcp__server1__tool2
    private String buildMcpInstructions(McpManager.ConnectResult result) {
        if (result.servers().isEmpty()) return null;
        var sb = new StringBuilder();
        sb.append("# MCP Server Instructions\n\n");
        sb.append("The following MCP servers are connected. "
                + "Use their tools when the user asks.\n");
        for (var server : result.servers()) {
            sb.append("\n## ").append(server.name()).append('\n');
            var toolNames = toolRegistry.listTools().stream()
                    .filter(t -> t.shouldDefer() && t.name().startsWith("mcp__" + McpManager.sanitizeName(server.name()) + "__"))
                    .map(Tool::name)
                    .toList();
            if (!toolNames.isEmpty()) {
                sb.append("Available tools: ").append(String.join(", ", toolNames));
            }
            if (server.instructions() != null && !server.instructions().isEmpty()) {
                sb.append('\n').append(server.instructions());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // 动态上下文注入，仅理出MCP可发现工具名。完整的sachema需要LLM使用ToolSearch发现
    private String buildDynamicContext() {
        var deferredNames = toolRegistry.getDeferredToolNames();
        if (deferredNames.isEmpty()) return null;
        var sb = new StringBuilder();
        sb.append("The following deferred tools are available via ToolSearch. ");
        sb.append("Their schemas are NOT loaded - use ToolSearch with ");
        sb.append("query \"select:<name>[,<name>...]\" to load tool schemas before calling them:\n");
        for (var dn : deferredNames) {
            sb.append(dn).append('\n');
        }
        return sb.toString();
    }

    public void clearConversation() {
        conversation.clear();
        // Reset session for new conversation
        currentSessionId = null;
        lastSavedIndex = 0;
        mcpInstructionsOk = false;
        // Clear active skills on conversation clear
        clearActiveSkills();
    }

    // ── Instructions, Memory, Session ───────────────────────

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    private void replaceInstructionsInConversation() {
        var messages = conversation.getMessagesMutable();
        if (messages.isEmpty()) return;
        // Find the first system-reminder that contains # claudeMd and replace it
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg.getContent() != null && msg.getContent().contains("# claudeMd")) {
                if (instructionsContent != null && !instructionsContent.isEmpty()) {
                    msg.setContent("<system-reminder>\n# claudeMd\n\n"
                            + instructionsContent + "\n</system-reminder>");
                }
                return;
            }
        }
        // Not found; prepend
        if (instructionsContent != null && !instructionsContent.isEmpty()) {
            conversation.addSystemReminder("# claudeMd\n\n" + instructionsContent);
        }
    }

    // Track last saved message index for incremental session persistence
    private int lastSavedIndex = 0;

    private void saveRecentMessages() {
        if (sessionManager == null || currentSessionId == null) return;
        var messages = conversation.getMessages();

        // Save all new messages since last save
        for (int i = lastSavedIndex; i < messages.size(); i++) {
            sessionManager.saveMessage(currentSessionId, messages.get(i));
        }
        lastSavedIndex = messages.size();

        // Update meta: find first real user message (skip system-reminders)
        String firstMsg = "";
        for (var msg : messages) {
            String content = msg.getContent();
            if ("user".equals(msg.getRole())
                    && content != null && !content.isEmpty()
                    && !content.startsWith("<system-reminder>")) {
                firstMsg = content;
                break;
            }
        }
        if (firstMsg.isEmpty() && !messages.isEmpty()) {
            firstMsg = messages.get(0).getContent();
        }
        if (firstMsg != null && firstMsg.length() > 100) {
            firstMsg = firstMsg.substring(0, 100) + "...";
        }
        String gitBranch = environmentContext != null ? environmentContext.gitBranch() : "";
        sessionManager.saveMeta(currentSessionId, new SessionManager.SessionInfo(
                currentSessionId, firstMsg, messages.size(), 0, gitBranch, java.time.Instant.now()));
    }

    private void triggerMemoryExtraction() {
        if (memoryManager == null) return;
        if (!memoryManager.shouldExtract()) return;
        var client = llmClient;
        var conv = conversation;
        Thread.startVirtualThread(() -> memoryManager.extract(client, conv));
    }

    /**
     * Shutdown hook: flush pending memory extraction with 5-second timeout.
     * Called when the application exits.
     */
    public void shutdown() {
        if (memoryManager == null) return;
        // If extraction would have been due this round, trigger it synchronously
        // with a 5-second timeout so we don't lose data on exit.
        if (memoryManager.getTurnCount() > 0 && memoryManager.shouldExtract()) {
            var client = llmClient;
            var conv = conversation;
            var future = new java.util.concurrent.FutureTask<>(() -> {
                memoryManager.extract(client, conv);
                return null;
            });
            Thread.startVirtualThread(future);
            try {
                future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("[LiCode] Memory extraction on shutdown timed out");
            }
        }
    }

    public ConversationManager getConversation() {
        return conversation;
    }

    public ProviderConfig getConfig() {
        return config;
    }

    // Session resume ─────────────────────────────────────────

    /**
     * Resumes a previous session.
     *
     * @param sessionId session ID or "latest" for the most recent session
     * @return status message for display, or null on failure
     */
    public String resumeSession(String sessionId) {
        if (sessionManager == null) return null;

        // Resolve "latest"
        if ("latest".equals(sessionId)) {
            var sessions = sessionManager.listSessions(null, null, null);
            if (sessions.isEmpty()) return "No previous sessions found.";
            sessionId = sessions.get(0).id();
        }

        // Load messages
        var rawMessages = sessionManager.loadSession(sessionId);
        if (rawMessages.isEmpty()) return "Session " + sessionId + " is empty or not found.";

        // Rebuild conversation (handles truncation, boundary)
        var rebuildResult = sessionManager.rebuildConversation(rawMessages);
        var messages = rebuildResult.messages();

        // Load messages into conversation first
        var mutableMessages = conversation.getMessagesMutable();
        mutableMessages.clear();
        mutableMessages.addAll(messages);

        // Check 24h time gap (after messages are loaded, before compaction)
        if (rebuildResult.lastActiveTime() != null) {
            int days = SessionManager.daysSince(rebuildResult.lastActiveTime());
            if (days >= 1) {
                long hours = java.time.Duration.between(rebuildResult.lastActiveTime(),
                        java.time.Instant.now()).toHours();
                conversation.addSystemReminder(
                        "This session was last active " + days + " day(s) ago ("
                                + hours + " hours). The current time is "
                                + java.time.LocalDateTime.now() + ".");
            }
        }

        // Check token overflow and compact if needed
        int estimatedTokens = ContextCompactor.estimateTokens(messages);
        int contextWindow = config.resolvedContextWindow();
        if (estimatedTokens > contextWindow * 0.8) {
            ContextCompactor.forceCompact(conversation, llmClient, contextWindow,
                    workDir, lastAgent != null ? lastAgent.getRecoveryState() : null,
                    lastAgent != null ? lastAgent.getCompactTracking() : null);
        }

        // Pin saved index to current conversation size so saveRecentMessages()
        // only persists new messages from this point forward
        lastSavedIndex = conversation.getMessages().size();
        currentSessionId = sessionId;
        return "Resumed session " + sessionId + " (" + messages.size() + " messages"
                + (rebuildResult.compacted() ? ", compacted" : "") + ")";
    }

    // Manual /compact command entry point
    public String compact() {
        Agent agent = lastAgent;
        if (agent == null) return "No active agent to compact.";
        return ContextCompactor.forceCompact(conversation, llmClient,
                config.resolvedContextWindow(),
                workDir, agent.getRecoveryState(), agent.getCompactTracking());
    }
}
