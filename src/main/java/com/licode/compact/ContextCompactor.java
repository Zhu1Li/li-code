package com.licode.compact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.licode.conversation.ConversationManager;
import com.licode.conversation.Message;
import com.licode.conversation.ThinkingBlock;
import com.licode.conversation.ToolResultBlock;
import com.licode.conversation.ToolUseBlock;
import com.licode.llm.LlmClient;
import com.licode.llm.StreamEvent;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * Two-layer context compaction: Layer 1 offloads and snips locally,
 * Layer 2 triggers a full LLM summary when tokens exceed threshold.
 */
public final class ContextCompactor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final double AUTOCOMPACT_THRESHOLD = 0.80;

    private static final int SUMMARY_OUTPUT_RESERVE = 20_000;
    private static final int AUTO_COMPACT_SAFETY_MARGIN = 13_000;
    private static final int MANUAL_COMPACT_SAFETY_MARGIN = 3_000;

    private static final int SINGLE_RESULT_LIMIT = 50_000;
    private static final int MESSAGE_AGGREGATE_LIMIT = 200_000;
    private static final int OLD_RESULT_SNIP_CHARS = 2_000;
    private static final int KEEP_RECENT_TURNS = 10;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final int KEEP_RECENT_TOKENS = 10_000;
    private static final int MIN_KEEP_MESSAGES = 5;
    private static final int KEEP_MAX_TOKENS = 40_000;

    private static final String SPILL_SUBDIR = ".licode/tool_results";

    /** Recovery limits applied to the attachment block appended after a Layer 2 summary. */
    public static final int RECOVERY_FILE_LIMIT = 5;
    private static final DateTimeFormatter RECOVERY_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    public static final String LI_CODE_SUMMARY_PROMPT = """
            Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.
            You MUST NOT call any tools during this summarization — produce text only.

            Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts. In your analysis:
            1. Chronologically walk through each message, identifying requests, approaches, decisions, file paths, errors and fixes, user feedback.
            2. Double-check for technical accuracy and completeness.

            After your analysis, output your final summary wrapped in <summary> tags. The summary MUST preserve the following 7 categories of information:

            1. 用户核心要求 (User Core Requirements):
               - Extract the user's explicit instructions, technical constraints, desired behavior, and provided examples.
               - For technical details (file paths, function names, parameter values, error messages, version numbers), quote them verbatim without rewriting.
               - For intent and requirements, summarize concisely but do not lose any conditional qualifiers (e.g., "only", "must", "do not", "if and only if").
               - Discard pure politeness and unhelpful conversational chatter.

            2. 当前目标 (Current Goal):
               - What is the immediate task being worked on?
               - What defines completion of this task?

            3. 关键概念与决策 (Key Concepts and Decisions):
               - Important architectural or design decisions made and their rationale.
               - Key technical concepts discussed.

            4. 涉及文件与代码 (Files and Code Involved):
               - All file paths that were read, modified, or created.
               - Key code snippets or patterns discussed.

            5. 错误与修复记录 (Errors and Fixes):
               - Errors encountered, their root causes, and how they were resolved.
               - What was tried that did NOT work, and why.

            6. 待办事项与当前进展 (Pending Work and Current Progress):
               - What has been completed so far.
               - What remains to be done.

            7. 下一步行动 (Next Steps):
               - The immediate next action to take.

            Output structure:

            <analysis>
            [Your thought process]
            </analysis>

            <summary>
            [The final compact summary covering all 7 categories above]
            </summary>

            Remember: You MUST NOT call any tools. Produce text only.""";

    private ContextCompactor() {}

    // ── Circuit Breaker ────────────────────────────────────────────────

    public static class AutoCompactTrackingState {
        private int consecutiveFailures;

        public boolean isTripped() {
            return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
        }

        public void recordFailure() {
            consecutiveFailures++;
        }

        public void reset() {
            consecutiveFailures = 0;
        }
    }

    // ── Usage Anchor ───────────────────────────────────────────────────

    /**
     * Real API-usage anchor for context-window accounting. Captured after each
     * stream ends: {@code baselineTokens} = input + cacheRead + cacheCreation +
     * output reported by the provider, and {@code anchorCount} = the number of
     * conversation messages present when that usage was measured. Everything
     * after {@code anchorCount} is estimated incrementally on top of the
     * baseline, so a cache hit (where the real input is far below the raw
     * character estimate) no longer inflates the compaction decision.
     */
    public record UsageAnchor(int baselineTokens, int anchorCount) {}

    // ── Public API ──────────────────────────────────────────────────────

    private static int computeCompactThreshold(int contextWindow, int maxOutput, boolean manual) {
        int reserve = SUMMARY_OUTPUT_RESERVE;
        if (maxOutput > 0 && maxOutput < reserve) {
            reserve = maxOutput;
        }
        int effectiveWindow = contextWindow - reserve;
        int margin = manual ? MANUAL_COMPACT_SAFETY_MARGIN : AUTO_COMPACT_SAFETY_MARGIN;
        return effectiveWindow - margin;
    }

    public static String manage(ConversationManager conv, LlmClient client,
                                int contextWindow, int maxOutput, String workDir,
                                AutoCompactTrackingState tracking,
                                RecoveryState recovery) {
        return manage(conv, client, contextWindow, maxOutput, workDir, tracking,
                recovery, null);
    }

    /**
     * Layer 1 + Layer 2 management. Layer 1 runs unconditionally (offload + snip).
     * Layer 2 fires when used tokens reach the auto-compact threshold; once they
     * cross the hard-block line it forces a compaction bypassing the circuit breaker.
     */
    public static String manage(ConversationManager conv, LlmClient client,
                                int contextWindow, int maxOutput, String workDir,
                                AutoCompactTrackingState tracking,
                                RecoveryState recovery,
                                UsageAnchor anchor) {
        String l1 = offloadAndSnip(conv, workDir);

        int tokens = currentTokens(conv.getMessages(), anchor);

        if (tokens < computeCompactThreshold(contextWindow, maxOutput, false)) {
            return l1;
        }

        if (tokens >= computeCompactThreshold(contextWindow, maxOutput, true)) {
            return forceCompact(conv, client, contextWindow, workDir, recovery, tracking);
        }

        if (tracking == null || !tracking.isTripped()) {
            try {
                String l2 = autoCompact(conv, client, contextWindow, workDir, recovery);
                if (tracking != null) tracking.reset();
                return l2;
            } catch (Exception e) {
                if (tracking != null) tracking.recordFailure();
            }
        }
        return l1;
    }

    /** Force a full auto-compact regardless of current token usage. */
    public static String forceCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      String workDir, RecoveryState recovery) {
        return forceCompact(conv, client, contextWindow, workDir, recovery, null);
    }

    /**
     * Force a full auto-compact regardless of current token usage.
     *
     * <p>On success this resets the auto-compact circuit breaker ({@code tracking}),
     * so that a manual {@code /compact}, a resume-time compaction, or an
     * error-driven force compaction restores automatic compaction after it has
     * tripped. Without this, once auto-compaction trips the breaker
     * ({@link AutoCompactTrackingState#isTripped()} stays true), the soft/hard
     * threshold band that calls {@link #reset()} is never re-entered and the
     * process never recovers automatic compaction on its own.
     *
     * @param tracking the circuit-breaker state to reset on success; may be null
     */
    public static String forceCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      String workDir, RecoveryState recovery,
                                      AutoCompactTrackingState tracking) {
        String result = autoCompact(conv, client, contextWindow, workDir, recovery);
        if (tracking != null) tracking.reset();
        return result;
    }

    /**
     * Current used-token estimate for the compaction decision.
     *
     * <p>With a real-usage {@code anchor}: {@code baselineTokens} plus a
     * character estimate of only the messages appended after the anchor
     * (index >= anchorCount). Without an anchor (cold start, before any stream
     * has reported usage) it falls back to estimating all messages.
     */
    public static int currentTokens(List<Message> messages, UsageAnchor anchor) {
        if (anchor == null || anchor.anchorCount() < 0
                || anchor.anchorCount() > messages.size()) {
            return estimateTokens(messages);
        }
        List<Message> appended = messages.subList(anchor.anchorCount(), messages.size());
        return anchor.baselineTokens() + estimateTokens(appended);
    }

    /** Estimate the token count for a list of messages using a simple heuristic. */
    public static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += (int) (safeLength(m.getContent()) / 3.5) + 4;

            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    String argsJson;
                    try {
                        argsJson = MAPPER.writeValueAsString(tu.arguments());
                    } catch (JsonProcessingException e) {
                        argsJson = "{}";
                    }
                    total += 50 + (int) (argsJson.length() / 3.5);
                }
            }

            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    total += (int) (safeLength(tr.content()) / 3.5) + 10;
                }
            }

            if (m.getThinkingBlocks() != null) {
                for (ThinkingBlock tb : m.getThinkingBlocks()) {
                    total += (int) (safeLength(tb.thinking()) / 3.5);
                }
            }
        }
        return total;
    }

    // ── Layer 1: Offload & Snip ────────────────────────────────────────

    static String offloadAndSnip(ConversationManager conv, String workDir) {
        List<Message> messages = conv.getMessagesMutable();
        if (messages.isEmpty()) return "";

        String spillDir = workDir != null
                ? Path.of(workDir, SPILL_SUBDIR).toString()
                : null;
        int spillCount = 0;
        int snipCount = 0;
        int savedChars = 0;
        boolean changed = false;

        int boundary = Math.max(0, messages.size() - KEEP_RECENT_TURNS * 3);

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg.getToolResults() == null) continue;

            List<ToolResultBlock> results = new ArrayList<>(msg.getToolResults());
            boolean msgChanged = false;

            // Per-result spill: single result above SINGLE_RESULT_LIMIT
            for (int j = 0; j < results.size(); j++) {
                ToolResultBlock tr = results.get(j);
                if (alreadyProcessed(tr.content()) || safeLength(tr.content()) <= SINGLE_RESULT_LIMIT) {
                    continue;
                }
                if (spillDir == null) continue;
                Path path = writeSpill(spillDir, tr.toolUseId(), tr.content());
                if (path == null) continue;

                savedChars += tr.content().length();
                results.set(j, new ToolResultBlock(
                        tr.toolUseId(),
                        String.format("[Result of %d chars saved to %s]",
                                tr.content().length(), path),
                        tr.isError()));
                spillCount++;
                msgChanged = true;
            }

            // Per-message aggregate spill
            int agg = 0;
            for (ToolResultBlock tr : results) {
                agg += safeLength(tr.content());
            }
            if (agg > MESSAGE_AGGREGATE_LIMIT && spillDir != null) {
                for (int j = 0; j < results.size(); j++) {
                    ToolResultBlock tr = results.get(j);
                    if (alreadyProcessed(tr.content()) || safeLength(tr.content()) <= 200) {
                        continue;
                    }
                    Path path = writeSpill(spillDir, tr.toolUseId(), tr.content());
                    if (path == null) continue;

                    savedChars += tr.content().length();
                    results.set(j, new ToolResultBlock(
                            tr.toolUseId(),
                            String.format("[Result of %d chars saved to %s]",
                                    tr.content().length(), path),
                            tr.isError()));
                    spillCount++;
                    msgChanged = true;
                }
            }

            // Snip stale results past the recent-turns boundary
            if (i < boundary) {
                for (int j = 0; j < results.size(); j++) {
                    ToolResultBlock tr = results.get(j);
                    if (alreadyProcessed(tr.content()) || safeLength(tr.content()) <= OLD_RESULT_SNIP_CHARS) {
                        continue;
                    }
                    results.set(j, new ToolResultBlock(
                            tr.toolUseId(),
                            String.format("[Stale output snipped: %d chars]", tr.content().length()),
                            tr.isError()));
                    snipCount++;
                    msgChanged = true;
                }
            }

            if (msgChanged) {
                msg.setToolResults(results);
                changed = true;
            }
        }

        if (!changed) return "";
        rebuildConversation(conv, messages);

        var parts = new ArrayList<String>();
        if (spillCount > 0) parts.add(String.format("spilled %d tool result(s) to disk", spillCount));
        if (snipCount > 0) parts.add(String.format("snipped %d stale result(s)", snipCount));
        return String.format("%s (~%d chars freed)", String.join("; ", parts), savedChars);
    }

    // ── Layer 2: Auto-compact ──────────────────────────────────────────

    /**
     * Pick the index where the verbatim "keep" tail begins.
     *
     * <p>Walk backwards from the end accumulating each message's estimated
     * tokens. Stop once either floor (KEEP_RECENT_TOKENS or MIN_KEEP_MESSAGES)
     * is met. Capped by KEEP_MAX_TOKENS.
     *
     * <p>Pairing protection: a {@code user} message carrying tool_result blocks
     * must never be kept without its originating assistant tool_use message.
     */
    static int computeKeepStartIndex(List<Message> messages) {
        int n = messages.size();
        if (n == 0) return 0;

        int accumulated = 0;
        int kept = 0;
        int keepStart = n;
        for (int i = n - 1; i >= 0; i--) {
            int msgTokens = estimateTokens(List.of(messages.get(i)));
            if (accumulated + msgTokens > KEEP_MAX_TOKENS && kept > 0) {
                break;
            }
            accumulated += msgTokens;
            kept++;
            keepStart = i;
            if (accumulated >= KEEP_RECENT_TOKENS || kept >= MIN_KEEP_MESSAGES) {
                break;
            }
        }

        while (keepStart > 0 && isToolResultMessage(messages.get(keepStart))) {
            keepStart--;
        }
        return keepStart;
    }

    private static boolean isToolResultMessage(Message m) {
        return "user".equals(m.getRole())
                && m.getToolResults() != null
                && !m.getToolResults().isEmpty();
    }

    private static String autoCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      String workDir,
                                      RecoveryState recovery) {
        List<Message> messages = conv.getMessages();
        int beforeTokens = estimateTokens(messages);

        int keepStartIndex = computeKeepStartIndex(messages);

        if (keepStartIndex <= 0 || keepStartIndex < MIN_KEEP_MESSAGES) {
            return "";
        }

        List<Message> toSummarize = messages.subList(0, keepStartIndex);
        List<Message> toKeep = messages.subList(keepStartIndex, messages.size());

        String serialized = serializeForSummary(toSummarize, 500);
        String summaryRaw = requestSummary(client,
                LI_CODE_SUMMARY_PROMPT + "\n\n" + serialized);
        String summaryText = formatCompactSummary(summaryRaw);

        String content = "本次会话延续自之前的对话，因上下文空间不足进行了压缩。以下是早期对话的摘要：\n\n" + summaryText;
        if (!toKeep.isEmpty()) {
            content += "\n\n近期消息已原样保留。";
        }
        String attachment = buildRecoveryAttachment(recovery, workDir);
        if (!attachment.isEmpty()) {
            content += "\n\n---\n\n" + attachment;
        }

        ConversationManager compacted = new ConversationManager();
        compacted.addUserMessage(content);
        for (Message m : toKeep) {
            appendMessage(compacted, m);
        }

        replaceConversation(conv, compacted);

        int afterTokens = estimateTokens(conv.getMessages());
        return String.format("Compacted: %d -> %d estimated tokens", beforeTokens, afterTokens);
    }

    // ── Post-compact recovery attachment ───────────────────────────────

    /**
     * Build the post-compaction recovery block appended to the summary message.
     *
     * <p>Philosophy: provide an <i>index</i> (hard facts + references), not a
     * content dump. The model already has a system prompt with tool definitions
     * and the verbatim tail of recent messages. This block gives it enough
     * ground truth to re-establish state: what branch we are on, which files
     * were touched, which skills were active — all verifiable, not LLM-generated.
     *
     * <p>Sections (emitted only when data is available):
     * <ol>
     *   <li><b>Current State</b> — git branch + modified files (from real git, not summary)</li>
     *   <li><b>Recently read files</b> — paths + timestamps only; model is told to re-read if needed</li>
     *   <li><b>Active skills</b> — names only; model is told to reload with LoadSkill if still in scope</li>
     * </ol>
     *
     * @param state  recovery snapshots from the agent (may be null)
     * @param workDir working directory for git inspection (may be null)
     * @return the attachment markdown, or "" when there is nothing worth emitting
     */
    public static String buildRecoveryAttachment(RecoveryState state, String workDir) {
        var sb = new StringBuilder();
        boolean emitted = false;

        // ── Section 1: Hard facts (not LLM-generated) ──

        if (workDir != null) {
            String gitInfo = gatherGitState(workDir);
            if (!gitInfo.isEmpty()) {
                sb.append("## Current State\n\n");
                sb.append(gitInfo);
                emitted = true;
            }
        }

        // ── Section 2: Index — references, not content copies ──

        if (state != null) {
            var files = state.snapshotFiles(RECOVERY_FILE_LIMIT);
            if (!files.isEmpty()) {
                sb.append("### Recently read files\n\n");
                sb.append("These files were read before compaction. ");
                sb.append("Re-open with the Read tool if you need current content.\n\n");
                for (var f : files) {
                    sb.append("- `").append(f.path()).append("`")
                      .append(" (read ").append(RECOVERY_TS.format(f.timestamp())).append(")\n");
                }
                sb.append('\n');
                emitted = true;
            }

            var skills = state.snapshotSkills();
            if (!skills.isEmpty()) {
                sb.append("### Active skills\n\n");
                sb.append("These skills were active before compaction. ");
                sb.append("Re-invoke with LoadSkill if the task is still within scope.\n\n");
                for (var sk : skills) {
                    sb.append("- `").append(sk.name()).append("`\n");
                }
                sb.append('\n');
                emitted = true;
            }
        }

        if (!emitted) return "";

        // ── Guidance ──

        sb.append("## Note\n\n");
        sb.append("Above is reconstructed state, NOT the original conversation. ")
          .append("Key rules:\n");
        sb.append("- Verify before acting: re-read files, check git status, confirm task progress.\n");
        sb.append("- Do not trust recalled code snippets — they may be stale. Re-read the source.\n");
        sb.append("- If a listed skill is still relevant, reload it with LoadSkill.\n");

        return sb.toString();
    }

    // ── Git state helper ──────────────────────────────────────────────

    private static String gatherGitState(String workDir) {
        try {
            Path dir = Path.of(workDir);
            if (!Files.isDirectory(dir.resolve(".git"))) return "";

            var sb = new StringBuilder();

            // Current branch
            String branch = runGitOneLine(dir, "branch", "--show-current");
            if (!branch.isEmpty()) {
                sb.append("- Git branch: `").append(branch).append("`\n");
            }

            // Modified / staged / untracked files
            String status = runGitOneLine(dir, "status", "--short");
            if (!status.isEmpty()) {
                sb.append("- Working tree:\n");
                int cap = 0;
                for (String line : status.split("\n")) {
                    if (cap > 3000) {
                        sb.append("  ... (truncated)\n");
                        break;
                    }
                    String trimmed = line.strip();
                    if (!trimmed.isEmpty()) {
                        sb.append("  `").append(trimmed).append("`\n");
                        cap += trimmed.length() + 10;
                    }
                }
            } else if (!branch.isEmpty()) {
                sb.append("- Working tree: clean\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return ""; // git not installed, not a repo, or other error — skip gracefully
        }
    }

    private static String runGitOneLine(Path dir, String... args) {
        try {
            var pb = new ProcessBuilder("git");
            for (String a : args) pb.command().add(a);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).strip();
            p.waitFor();
            return out;
        } catch (Exception e) {
            return "";
        }
    }
    // ── Helpers ─────────────────────────────────────────────────────────

    private static boolean alreadyProcessed(String s) {
        return s != null && (s.startsWith("[Result of ") || s.startsWith("[Stale output snipped:"));
    }

    private static Path writeSpill(String spillDir, String toolUseId, String content) {
        try {
            Path dir = Path.of(spillDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(toolUseId);
            Files.writeString(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return file;
        } catch (FileAlreadyExistsException e) {
            return Path.of(spillDir).resolve(toolUseId);
        } catch (IOException e) {
            return null;
        }
    }

    static String formatCompactSummary(String raw) {
        int start = raw.indexOf("<summary>");
        int end = raw.indexOf("</summary>");
        if (start >= 0 && end > start) {
            return raw.substring(start + "<summary>".length(), end).strip();
        }
        return raw.strip();
    }

    private static String requestSummary(LlmClient client, String prompt) {
        ConversationManager summaryConv = new ConversationManager();
        summaryConv.addUserMessage(prompt);

        BlockingQueue<StreamEvent> events = client.stream(summaryConv, null);
        var summary = new StringBuilder();

        try {
            while (true) {
                StreamEvent ev = events.take();
                if (ev instanceof StreamEvent.TextDelta td) {
                    summary.append(td.text());
                } else if (ev instanceof StreamEvent.Error err) {
                    throw new RuntimeException("LLM summary failed: " + err.message());
                } else if (ev instanceof StreamEvent.StreamEnd) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Summary interrupted", e);
        }

        return summary.toString();
    }

    private static String serializeForSummary(List<Message> messages, int toolResultCap) {
        var sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(String.format("[%s]: %s\n", m.getRole(), nullSafe(m.getContent())));
            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    sb.append(String.format("[tool_use %s]: %s\n", tu.toolName(), tu.toolUseId()));
                }
            }
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    String content = nullSafe(tr.content());
                    if (content.length() > toolResultCap) {
                        content = content.substring(0, toolResultCap) + "...";
                    }
                    sb.append(String.format("[tool_result]: %s\n", content));
                }
            }
        }
        return sb.toString();
    }

    private static void appendMessage(ConversationManager conv, Message m) {
        if (m.getToolUses() != null && !m.getToolUses().isEmpty()) {
            conv.addAssistantFull(m.getContent(), m.getThinkingBlocks(), m.getToolUses(), m.getToolResults());
        } else if (m.getToolResults() != null && !m.getToolResults().isEmpty()) {
            conv.addToolResultsMessage(m.getToolResults());
        } else if ("user".equals(m.getRole())) {
            conv.addUserMessage(m.getContent());
        } else if ("assistant".equals(m.getRole())) {
            conv.addAssistantMessage(m.getContent());
        }
    }

    private static void rebuildConversation(ConversationManager conv, List<Message> messages) {
        ConversationManager rebuilt = new ConversationManager();
        for (Message m : messages) {
            appendMessage(rebuilt, m);
        }
        replaceConversation(conv, rebuilt);
    }

    private static void replaceConversation(ConversationManager target, ConversationManager source) {
        List<Message> targetList = target.getMessagesMutable();
        targetList.clear();
        targetList.addAll(source.getMessages());
    }

    private static int safeLength(String s) {
        return s == null ? 0 : s.length();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
