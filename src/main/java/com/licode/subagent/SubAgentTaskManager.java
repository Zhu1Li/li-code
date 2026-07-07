package com.licode.subagent;

import com.licode.agent.Agent;
import com.licode.agent.AgentEvent;
import com.licode.conversation.ConversationManager;
import com.licode.hook.HookEngine;
import com.licode.llm.LlmClient;
import com.licode.permission.PermissionChecker;
import com.licode.permission.PermissionMode;
import com.licode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory task manager for tracking background sub-agent tasks.
 * Accumulates notifications that the parent agent can drain between turns.
 */
public class SubAgentTaskManager {

    public enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

    public record Task(String id, String name, TaskStatus status, String output, String error,
                       long inputTokens, long outputTokens, long elapsedMs) {}

    public record TaskNotification(String taskId, String name, TaskStatus status, String output,
                                   long inputTokens, long outputTokens, long elapsedMs) {}

    private final Map<String, TaskEntry> tasks = new LinkedHashMap<>();
    private final List<TaskNotification> notifications = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger();

    private static class TaskEntry {
        final String id;
        final String name;
        volatile TaskStatus status;
        volatile String output;
        volatile String error;
        volatile Thread thread;
        volatile long inputTokens;
        volatile long outputTokens;
        volatile long elapsedMs;

        TaskEntry(String id, String name) {
            this.id = id;
            this.name = name;
            this.status = TaskStatus.PENDING;
        }
    }

    public synchronized String createTask(String name) {
        String id = "task_" + nextId.incrementAndGet();
        tasks.put(id, new TaskEntry(id, name));
        return id;
    }

    public synchronized void setRunning(String id, Thread thread) {
        TaskEntry t = tasks.get(id);
        if (t != null) {
            t.status = TaskStatus.RUNNING;
            t.thread = thread;
        }
    }

    public synchronized void setCompleted(String id, String output,
                                          long inputTokens, long outputTokens, long elapsedMs) {
        TaskEntry t = tasks.get(id);
        if (t != null) {
            t.status = TaskStatus.COMPLETED;
            t.output = output;
            t.inputTokens = inputTokens;
            t.outputTokens = outputTokens;
            t.elapsedMs = elapsedMs;
            notifications.add(new TaskNotification(id, t.name, TaskStatus.COMPLETED, output,
                    inputTokens, outputTokens, elapsedMs));
        }
    }

    public synchronized void setFailed(String id, String errMsg) {
        TaskEntry t = tasks.get(id);
        if (t != null) {
            t.status = TaskStatus.FAILED;
            t.error = errMsg;
            notifications.add(new TaskNotification(id, t.name, TaskStatus.FAILED, errMsg, 0, 0, 0));
        }
    }

    public synchronized void cancelTask(String id) {
        TaskEntry t = tasks.get(id);
        if (t != null && t.status == TaskStatus.RUNNING) {
            t.status = TaskStatus.CANCELLED;
            if (t.thread != null) {
                t.thread.interrupt();
            }
            notifications.add(new TaskNotification(id, t.name, TaskStatus.CANCELLED, "",
                    t.inputTokens, t.outputTokens, t.elapsedMs));
        }
    }

    public synchronized List<TaskNotification> drainNotifications() {
        var result = new ArrayList<>(notifications);
        notifications.clear();
        return result;
    }

    public synchronized Task getTask(String id) {
        TaskEntry t = tasks.get(id);
        if (t == null) return null;
        return new Task(t.id, t.name, t.status, t.output, t.error,
                t.inputTokens, t.outputTokens, t.elapsedMs);
    }

    public synchronized List<Task> listTasks() {
        return tasks.values().stream()
                .map(t -> new Task(t.id, t.name, t.status, t.output, t.error,
                        t.inputTokens, t.outputTokens, t.elapsedMs))
                .toList();
    }

    /**
     * Spawn a sub-agent in a background virtual thread, tracked by this manager.
     *
     * @param client       LLM client for the sub-agent
     * @param parentRegistry parent tool registry (will be filtered)
     * @param protocol     LLM protocol string
     * @param spec         sub-agent specification
     * @param prompt       task description injected as first user message
     * @param conv         pre-seeded conversation (for fork path) or null (for definition-based)
     * @param hookEngine   hook engine to wire (may be null)
     * @param workDir      working directory for the sub-agent
     * @param contextWindow context window size
     * @param maxOutput    max output tokens
     * @return the task ID
     */
    public String spawnSubAgent(
            LlmClient client,
            ToolRegistry parentRegistry,
            String protocol,
            SubAgentSpec spec,
            String prompt,
            ConversationManager conv,
            HookEngine hookEngine,
            String workDir,
            int contextWindow,
            int maxOutput
    ) {
        String taskId = createTask(spec.name() + ": " + truncate(prompt, 50));

        // Capture effectively-final copies for the lambda
        final ConversationManager finalConv;
        if (conv != null) {
            finalConv = conv;
        } else {
            finalConv = new ConversationManager();
            if (spec.systemPrompt() != null && !spec.systemPrompt().isEmpty()) {
                finalConv.addSystemReminder(spec.systemPrompt());
            }
            finalConv.addUserMessage(prompt);
        }

        Thread thread = Thread.startVirtualThread(() -> {
            long startTime = System.currentTimeMillis();
            ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec, true);
            var subAgent = new Agent(client, subRegistry, protocol);
            int maxTurns = spec.maxTurns() > 0 ? spec.maxTurns() : 200;
            subAgent.setMaxIterations(maxTurns);
            if (hookEngine != null) {
                subAgent.setHookEngine(hookEngine);
            }
            subAgent.setPermissionChecker(new PermissionChecker(PermissionMode.BYPASS,
                    java.nio.file.Path.of(workDir != null ? workDir : System.getProperty("user.dir"))));
            if (contextWindow > 0) subAgent.setContextWindow(contextWindow);
            if (maxOutput > 0) subAgent.setMaxOutput(maxOutput);
            if (workDir != null) subAgent.setWorkDir(workDir);

            var output = new StringBuilder();
            long totalInputTokens = 0;
            long totalOutputTokens = 0;
            BlockingQueue<AgentEvent> queue = subAgent.run(finalConv);

            while (!Thread.currentThread().isInterrupted()) {
                AgentEvent event;
                try {
                    event = queue.poll(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    setFailed(taskId, "Interrupted");
                    return;
                }
                if (event == null) {
                    setFailed(taskId, "Timeout after 120s");
                    return;
                }

                switch (event) {
                    case AgentEvent.StreamText st -> output.append(st.text());
                    case AgentEvent.UsageEvent ue -> {
                        totalInputTokens += ue.inputTokens();
                        totalOutputTokens += ue.outputTokens();
                    }
                    case AgentEvent.ErrorEvent err -> {
                        setFailed(taskId, err.message());
                        return;
                    }
                    case AgentEvent.LoopComplete lc -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        setCompleted(taskId,
                                output.isEmpty() ? "(agent produced no output)" : output.toString(),
                                totalInputTokens, totalOutputTokens, elapsed);
                        return;
                    }
                    default -> {}
                }
            }
            // If we get here, thread was interrupted
            setFailed(taskId, "Interrupted");
        });

        setRunning(taskId, thread);
        return taskId;
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
