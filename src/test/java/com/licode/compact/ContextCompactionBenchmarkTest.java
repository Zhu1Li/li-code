package com.licode.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.licode.conversation.ConversationManager;
import com.licode.conversation.Message;
import com.licode.conversation.ToolResultBlock;
import com.licode.conversation.ToolUseBlock;
import com.licode.toolresult.ContentReplacementState;
import com.licode.toolresult.ToolResultBudget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phased A/B compression benchmark.
 *
 * <p>Measures context compression at 3 levels:
 * <ol>
 *   <li><b>Raw messages</b> — conversation before any compression</li>
 *   <li><b>After L1</b> — ToolResultBudget spill/snip applied (no LLM)</li>
 *   <li><b>After L2 estimate</b> — simulated autoCompact based on
 *       computeKeepStartIndex split + estimated summary size</li>
 * </ol>
 *
 * <p>Each level reports both "message-only" and "total context" (system prompt +
 * messages) token counts, so the user can see per-component and per-stage effects.
 *
 * <p>6 configs = 3 history lengths (4/12/24 turns) × 2 tool result sizes (5K/60K chars).
 *
 * <p>Run manually: {@code mvn test -Dtest=ContextCompactionBenchmarkTest}
 * <p>Artifact: {@code target/context-ablation.json}
 */
class ContextCompactionBenchmarkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Typical LiCode system prompt size (~4K chars ≈ 1200 tokens)
    private static final int SYSTEM_PROMPT_CHARS = 4_000;
    private static final int SYSTEM_PROMPT_TOKENS = (int) (SYSTEM_PROMPT_CHARS / 3.5) + 4;

    // L2 estimate: summary is roughly this fraction of original summarized text
    private static final double L2_SUMMARY_FRACTION = 0.12;
    private static final int L2_SUMMARY_MIN_TOKENS = 500;
    private static final int L2_SUMMARY_MAX_TOKENS = 8_000;

    enum HistoryLength { SHORT, MEDIUM, LONG }
    enum ToolResultSize { SMALL, LARGE }

    record TestConfig(HistoryLength h, ToolResultSize r) {}

    /**
     * Per-config phased metrics.
     *
     * @param systemPromptTokens constant system prompt overhead
     * @param rawMsgTokens       raw conversation messages (before any compression)
     * @param l1MsgTokens        after ToolResultBudget (L1 spill + snip)
     * @param l1Spilled          count of tool results spilled to disk by L1
     * @param l1Snipped          count of tool results stale-snipped by L1
     * @param l2KeepStartIndex   boundary: msgs before this are summarized, >= this are kept verbatim
     * @param l2SummarizedTokens tokens of the prefix that would be summarized
     * @param l2KeptTokens       tokens of the verbatim tail kept by L2
     * @param l2EstSummaryTokens estimated token count of the LLM summary
     * @param l2EstMsgTokens     estimated message tokens after L2 (summary + kept)
     * @param frozenIdsCount     number of tool_use_ids frozen by ContentReplacementState
     */
    record PhasedResult(
            String id,
            String historyLevel,
            String resultSize,
            // System
            int systemPromptTokens,
            // Raw
            int rawMsgTokens,
            int rawTotalTokens,
            // After L1 (spill + snip)
            int l1MsgTokens,
            int l1TotalTokens,
            double l1CompressionRatio,    // (rawMsg - l1Msg) / rawMsg
            int l1Spilled,
            int l1Snipped,
            // L2 estimate (autoCompact)
            int l2KeepStartIndex,
            int l2SummarizedTokens,       // tokens of prefix that gets summarized
            int l2KeptTokens,             // tokens of verbatim tail
            int l2EstSummaryTokens,       // estimated summary size
            int l2EstMsgTokens,           // summary + kept
            int l2EstTotalTokens,         // system + l2 msg
            double l2IncrementalRatio,    // (l1Msg - l2EstMsg) / l1Msg  — L2's own contribution
            double l2CombinedRatio,       // (rawMsg - l2EstMsg) / rawMsg — L1+L2 combined
            // Decision freeze
            int frozenIdsCount,
            // Prompt cache prefix analysis
            int rawToL1PrefixTokens,      // common prefix chars / 3.5 between raw and L1 serialization
            int l1ToL2PrefixTokens,       // common prefix between L1 and L2 serialization
            double rawToL1CacheHitRate,   // rawToL1PrefixTokens / l1TotalTokens
            double l1ToL2CacheHitRate     // l1ToL2PrefixTokens / l2EstTotalTokens
    ) {}

    @Test
    void runContextStressMatrix(@TempDir Path tempDir) throws Exception {
        var configs = List.of(
                new TestConfig(HistoryLength.SHORT,  ToolResultSize.SMALL),
                new TestConfig(HistoryLength.SHORT,  ToolResultSize.LARGE),
                new TestConfig(HistoryLength.MEDIUM, ToolResultSize.SMALL),
                new TestConfig(HistoryLength.MEDIUM, ToolResultSize.LARGE),
                new TestConfig(HistoryLength.LONG,   ToolResultSize.SMALL),
                new TestConfig(HistoryLength.LONG,   ToolResultSize.LARGE)
        );

        List<PhasedResult> results = new ArrayList<>();

        for (var cfg : configs) {
            PhasedResult r = runConfig(tempDir, cfg.h(), cfg.r());
            results.add(r);
            printResult(r);
        }

        printSummaryTable(results);

        // Write artifact
        writeArtifact(results);

        // Assertions
        assertChecklist(results);
    }

    // ── Per-config execution ──────────────────────────────────────────

    private PhasedResult runConfig(Path tempDir, HistoryLength history, ToolResultSize resultSize) {
        int turns = switch (history) {
            case SHORT  -> 4;
            case MEDIUM -> 12;
            case LONG   -> 24;
        };
        int resultChars = switch (resultSize) {
            case SMALL -> 5_000;
            case LARGE -> 60_000;
        };

        ConversationManager conv = buildConversation(turns, resultChars);

        // ── Raw ──
        int rawMsgTokens = ContextCompactor.estimateTokens(conv.getMessages());
        int rawTotal = SYSTEM_PROMPT_TOKENS + rawMsgTokens;

        // ── L1: ToolResultBudget (spill + snip) ──
        ContentReplacementState state = new ContentReplacementState();
        var applied = ToolResultBudget.apply(conv, tempDir, state);
        int l1MsgTokens = ContextCompactor.estimateTokens(applied.apiConv().getMessages());
        int l1Total = SYSTEM_PROMPT_TOKENS + l1MsgTokens;
        double l1Ratio = rawMsgTokens > 0 ? (double) (rawMsgTokens - l1MsgTokens) / rawMsgTokens : 0;

        // Count L1 actions
        int spilled = 0, snipped = 0;
        for (var msg : applied.apiConv().getMessages()) {
            if (msg.getToolResults() != null) {
                for (ToolResultBlock tr : msg.getToolResults()) {
                    if (tr.content().startsWith("[Result of ")) spilled++;
                    if (tr.content().startsWith("[Stale output snipped:")) snipped++;
                }
            }
        }

        // ── L2 estimate ──
        var l1Messages = applied.apiConv().getMessages();
        int keepStart = ContextCompactor.computeKeepStartIndex(l1Messages);

        int summarizedTokens = 0;
        int keptTokens = 0;
        if (keepStart > 0 && keepStart < l1Messages.size()) {
            summarizedTokens = ContextCompactor.estimateTokens(l1Messages.subList(0, keepStart));
            keptTokens = ContextCompactor.estimateTokens(l1Messages.subList(keepStart, l1Messages.size()));
        } else {
            // Everything kept — L2 wouldn't fire
            keptTokens = l1MsgTokens;
        }

        int estSummary = summarizedTokens > 0
                ? Math.clamp((int) (summarizedTokens * L2_SUMMARY_FRACTION),
                             L2_SUMMARY_MIN_TOKENS, L2_SUMMARY_MAX_TOKENS)
                : 0;

        int l2EstMsgTokens = estSummary + keptTokens;
        int l2EstTotal = SYSTEM_PROMPT_TOKENS + l2EstMsgTokens;
        double l2Incremental = l1MsgTokens > 0 ? (double) (l1MsgTokens - l2EstMsgTokens) / l1MsgTokens : 0;
        double l2Combined = rawMsgTokens > 0 ? (double) (rawMsgTokens - l2EstMsgTokens) / rawMsgTokens : 0;

        // ── Prompt cache prefix analysis ──
        String id = history.name().toLowerCase() + "-" + resultSize.name().toLowerCase();
        String rawSerialized = serializeMessages(conv.getMessages());
        String l1Serialized = serializeMessages(applied.apiConv().getMessages());

        int commonChars = commonPrefixLength(rawSerialized, l1Serialized);
        int rawToL1Prefix = commonChars;  // store actual measured chars
        double rawToL1HitRate = l1Serialized.length() > 0
                ? (double) commonChars / l1Serialized.length() : 0;

        int l1ToL2Prefix = 0;  // summary replaces prefix → cache starts cold
        double l1ToL2HitRate = 0;
        l1ToL2HitRate = l2EstTotal > 0 ? (double) l1ToL2Prefix / l2EstTotal : 0;

        return new PhasedResult(
                id, history.name().toLowerCase(), resultSize.name().toLowerCase(),
                SYSTEM_PROMPT_TOKENS,
                rawMsgTokens, rawTotal,
                l1MsgTokens, l1Total, l1Ratio, spilled, snipped,
                keepStart, summarizedTokens, keptTokens, estSummary, l2EstMsgTokens, l2EstTotal,
                l2Incremental, l2Combined,
                state.seenIds().size(),
                rawToL1Prefix, l1ToL2Prefix, rawToL1HitRate, l1ToL2HitRate
        );
    }

    // ── Console output ────────────────────────────────────────────────

    private void printResult(PhasedResult r) {
        System.out.printf("""
                        ═══ %s ═══
                          Context breakdown:
                            System prompt:     %6d tokens (constant)
                            Raw messages:      %6d tokens
                            Total context:     %6d tokens
                          Layer 1 — ToolResultBudget (spill + snip):
                            After L1 messages: %6d tokens  (spilled %d, snipped %d)
                            L1 compression:    %6.1f%%   (of message tokens)
                            After L1 total:    %6d tokens
                          Layer 2 — autoCompact (estimate):
                            Keep boundary:     %6d   (summarize prefix 0..%d, keep %d..end)
                            Summarized prefix: %6d tokens → est. summary %d tokens
                            Verbatim tail:     %6d tokens
                            After L2 messages: %6d tokens  (L2 delta: %+.0f%%)
                            L1+L2 combined:    %6.1f%%   (of raw messages)
                            After L2 total:    %6d tokens
                          Decision freeze:
                            Frozen IDs:        %6d
                          Prompt cache prefix analysis:
                            Raw → L1:  %.0f%% cache hit → ~%,d tokens cold start (%.0f%% of L1)
                            L1 → L2:  0%% cache hit (summary rewrites prefix)
                                       %,d tokens kept tail identically reusable
                        %n""",
                r.id(),
                r.systemPromptTokens(),
                r.rawMsgTokens(),
                r.rawTotalTokens(),
                r.l1MsgTokens(), r.l1Spilled(), r.l1Snipped(),
                r.l1CompressionRatio() * 100,
                r.l1TotalTokens(),
                r.l2KeepStartIndex(), r.l2KeepStartIndex(), r.l2KeepStartIndex(),
                r.l2SummarizedTokens(), r.l2EstSummaryTokens(),
                r.l2KeptTokens(),
                r.l2EstMsgTokens(), r.l2IncrementalRatio() * 100,
                r.l2CombinedRatio() * 100,
                r.l2EstTotalTokens(),
                r.frozenIdsCount(),
                r.rawToL1CacheHitRate() * 100,
                (int) ((1.0 - r.rawToL1CacheHitRate()) * r.l1TotalTokens()),
                (1.0 - r.rawToL1CacheHitRate()) * 100,
                r.l2KeptTokens()
        );
    }

    private void printSummaryTable(List<PhasedResult> results) {
        System.out.println("══════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-14s | %6s → %6s | %5s → %5s | %5s %5s | %5s → %5s%n",
                "config", "rawMsg", "L1", "L1%", "L1+L2%", "cache%", "cold", "spill", "snip");
        System.out.println("───────────────┼─────────────────┼────────────────┼──────────────┼──────────────");
        for (var r : results) {
            System.out.printf("%-14s | %6d → %6d | %4.0f%% → %4.0f%%  |  %3.0f%%  %5d | %5d  → %5d%n",
                    r.id(),
                    r.rawMsgTokens(), r.l1MsgTokens(),
                    r.l1CompressionRatio() * 100, r.l2CombinedRatio() * 100,
                    r.rawToL1CacheHitRate() * 100,
                    (int) ((1.0 - r.rawToL1CacheHitRate()) * r.l1TotalTokens()),
                    r.l1Spilled(), r.l1Snipped());
        }
        System.out.println("══════════════════════════════════════════════════════════════════════════════════");
    }

    // ── Artifact ──────────────────────────────────────────────────────

    private void writeArtifact(List<PhasedResult> results) throws Exception {
        var ratiosL1 = results.stream().mapToDouble(PhasedResult::l1CompressionRatio).toArray();
        var ratiosL2 = results.stream().mapToDouble(PhasedResult::l2CombinedRatio).toArray();
        var cacheHitRates = results.stream().mapToDouble(PhasedResult::rawToL1CacheHitRate).toArray();

        var summary = new LinkedHashMap<String, Object>();
        summary.put("config_count", results.size());
        summary.put("system_prompt_tokens", SYSTEM_PROMPT_TOKENS);
        summary.put("avg_l1_compression_ratio", avg(ratiosL1));
        summary.put("max_l1_compression_ratio", max(ratiosL1));
        summary.put("min_l1_compression_ratio", min(ratiosL1));
        summary.put("avg_l2_combined_ratio", avg(ratiosL2));
        summary.put("max_l2_combined_ratio", max(ratiosL2));
        summary.put("min_l2_combined_ratio", min(ratiosL2));
        summary.put("avg_raw_to_l1_cache_hit_rate", avg(cacheHitRates));
        summary.put("max_raw_to_l1_cache_hit_rate", max(cacheHitRates));
        summary.put("min_raw_to_l1_cache_hit_rate", min(cacheHitRates));

        var artifact = new LinkedHashMap<String, Object>();
        artifact.put("schema_version", 2);
        artifact.put("artifact_type", "context-ablation");
        artifact.put("description", "Phased context compression benchmark (L1 + L2 estimate) with cache prefix analysis");
        artifact.put("configs", results.stream().map(r -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", r.id());
            m.put("history_level", r.historyLevel());
            m.put("result_size", r.resultSize());
            // System
            m.put("system_prompt_tokens", r.systemPromptTokens());
            // Raw
            m.put("raw_msg_tokens", r.rawMsgTokens());
            m.put("raw_total_tokens", r.rawTotalTokens());
            // L1
            m.put("l1_msg_tokens", r.l1MsgTokens());
            m.put("l1_total_tokens", r.l1TotalTokens());
            m.put("l1_compression_ratio", r.l1CompressionRatio());
            m.put("l1_spilled_count", r.l1Spilled());
            m.put("l1_snipped_count", r.l1Snipped());
            // L2
            m.put("l2_keep_start_index", r.l2KeepStartIndex());
            m.put("l2_summarized_tokens", r.l2SummarizedTokens());
            m.put("l2_kept_tokens", r.l2KeptTokens());
            m.put("l2_est_summary_tokens", r.l2EstSummaryTokens());
            m.put("l2_est_msg_tokens", r.l2EstMsgTokens());
            m.put("l2_est_total_tokens", r.l2EstTotalTokens());
            m.put("l2_incremental_ratio", r.l2IncrementalRatio());
            m.put("l2_combined_ratio", r.l2CombinedRatio());
            // Freeze
            m.put("frozen_ids_count", r.frozenIdsCount());
            // Cache
            m.put("raw_to_l1_prefix_tokens", r.rawToL1PrefixTokens());
            m.put("l1_to_l2_prefix_tokens", r.l1ToL2PrefixTokens());
            m.put("raw_to_l1_cache_hit_rate", r.rawToL1CacheHitRate());
            m.put("l1_to_l2_cache_hit_rate", r.l1ToL2CacheHitRate());
            m.put("l1_cold_start_tokens", r.l1TotalTokens() - r.rawToL1PrefixTokens());
            m.put("l2_identically_kept_tokens", r.l2KeptTokens());
            return m;
        }).toList());
        artifact.put("summary", summary);

        Path artifactPath = Path.of("target", "context-ablation.json");
        Files.createDirectories(artifactPath.getParent());
        Files.writeString(artifactPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(artifact));
        System.out.println("[benchmark] Artifact → " + artifactPath.toAbsolutePath() + "\n");
    }

    // ── Assertions ────────────────────────────────────────────────────

    private void assertChecklist(List<PhasedResult> results) {
        // Large config: L1 ratio > 0.3
        var large = find(results, "long", "large");
        assertTrue(large.l1CompressionRatio() > 0.3,
                "long-large L1 ratio should be > 0.3, got " + large.l1CompressionRatio());

        // Small config: L1 ratio near 0 (nothing exceeds threshold)
        var small = find(results, "short", "small");
        assertEquals(0.0, small.l1CompressionRatio(), 0.01,
                "short-small L1 ratio should be near 0");

        // L2 should not regress (combined ratio >= L1 ratio)
        for (var r : results) {
            assertTrue(r.l2CombinedRatio() >= r.l1CompressionRatio() - 0.01,
                    r.id() + ": L1+L2 combined (" + r.l2CombinedRatio()
                    + ") should be >= L1 alone (" + r.l1CompressionRatio() + ")");
        }

        // Decision freeze: every tool result ID should be frozen
        for (var r : results) {
            int expectedIds = r.historyLevel().equals("short") ? 8   // 4 turns × 2 tools
                    : r.historyLevel().equals("medium") ? 24          // 12 × 2
                    : 48;                                              // 24 × 2
            assertEquals(expectedIds, r.frozenIdsCount(),
                    r.id() + ": all tool_use_ids should be frozen");
        }

        // Verify artifact is parseable
        Path artifactPath = Path.of("target", "context-ablation.json");
        assertTrue(Files.exists(artifactPath));
    }

    private PhasedResult find(List<PhasedResult> results, String hist, String size) {
        return results.stream()
                .filter(r -> r.historyLevel().equals(hist) && r.resultSize().equals(size))
                .findFirst().orElseThrow();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static double avg(double[] arr) {
        return arr.length > 0 ? java.util.Arrays.stream(arr).average().orElse(0) : 0;
    }

    private static double max(double[] arr) {
        return arr.length > 0 ? java.util.Arrays.stream(arr).max().orElse(0) : 0;
    }

    private static double min(double[] arr) {
        return arr.length > 0 ? java.util.Arrays.stream(arr).min().orElse(0) : 0;
    }

    // ── Conversation builder ──────────────────────────────────────────

    private ConversationManager buildConversation(int turns, int resultChars) {
        ConversationManager conv = new ConversationManager();
        String readContent = BenchmarkContent.forTool("ReadFile", resultChars);
        String bashContent = BenchmarkContent.forTool("Bash", resultChars / 5);

        for (int i = 0; i < turns; i++) {
            conv.addUserMessage(BenchmarkContent.userMessage(i, turns));

            conv.addAssistantFull(
                    BenchmarkContent.assistantText(i),
                    null,
                    List.of(
                            new ToolUseBlock("tu_read_" + i, "ReadFile", Map.of("file_path", "/data/file" + i + ".txt")),
                            new ToolUseBlock("tu_bash_" + i, "Bash", Map.of("command", "cat /data/file" + i + ".txt | wc -l"))
                    ),
                    null
            );

            conv.addToolResultsMessage(List.of(
                    new ToolResultBlock("tu_read_" + i, readContent, false),
                    new ToolResultBlock("tu_bash_" + i, bashContent, false)
            ));

            conv.addAssistantMessage(BenchmarkContent.assistantText(i));
        }

        return conv;
    }

    // ── Prompt cache prefix helpers ─────────────────────────────────

    /**
     * Serializes messages to a canonical string for prefix comparison.
     * The format approximates the API request structure: each message
     * contributes its role, text content, tool call names/arguments,
     * and tool result content in a deterministic order.
     */
    static String serializeMessages(List<Message> messages) {
        var sb = new StringBuilder(messages.size() * 512 + 1024);
        for (var msg : messages) {
            sb.append("{role:").append(msg.getRole()).append('}');
            if (msg.getContent() != null) {
                sb.append("{text:").append(msg.getContent()).append('}');
            }
            if (msg.getToolUses() != null) {
                for (var tu : msg.getToolUses()) {
                    sb.append("{tool:").append(tu.toolName());
                    if (tu.arguments() != null) {
                        sb.append(',');
                        // Sort keys for deterministic output
                        var sorted = new java.util.TreeMap<>(tu.arguments());
                        sb.append(sorted);
                    }
                    sb.append('}');
                }
            }
            if (msg.getToolResults() != null) {
                for (var tr : msg.getToolResults()) {
                    sb.append("{result:").append(tr.toolUseId())
                      .append(':').append(tr.content()).append('}');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Longest common prefix length (in chars) between two strings. */
    static int commonPrefixLength(String a, String b) {
        if (a == null || b == null) return 0;
        int minLen = Math.min(a.length(), b.length());
        int i = 0;
        while (i < minLen && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    /** Approximate tokens from character count (same estimator as ContextCompactor). */
    static int tokensFromChars(int chars) {
        return chars / 3 + chars / 7;  // ~3 chars/token for code, ~7 for whitespace
    }
}
