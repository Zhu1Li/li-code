package com.licode.compact;

import com.licode.conversation.ConversationManager;
import com.licode.conversation.Message;
import com.licode.conversation.ThinkingBlock;
import com.licode.conversation.ToolResultBlock;
import com.licode.conversation.ToolUseBlock;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Key-fact recall measurement for context compaction.
 *
 * <p>Compression ratio answers "how much did we shrink", but not "did we keep
 * what matters". This utility measures the latter: given a set of gold facts
 * that were embedded into a conversation, it checks how many survive in the
 * post-compaction context, bucketed by the 7 information categories that
 * {@link ContextCompactor#LI_CODE_SUMMARY_PROMPT} promises to preserve.
 *
 * <p>Pure functions, no LLM — a fact is "recalled" if its needle still appears
 * (as a whitespace-normalized substring) anywhere in the serialized context.
 */
final class KeyFactRecall {

    private KeyFactRecall() {}

    /** The 7 categories of information the summary prompt must preserve. */
    enum Category {
        USER_REQUIREMENT,   // 用户核心要求
        CURRENT_GOAL,       // 当前目标
        KEY_DECISION,       // 关键概念与决策
        FILES_CODE,         // 涉及文件与代码
        ERRORS_FIXES,       // 错误与修复记录
        PENDING,            // 待办事项与当前进展
        NEXT_STEPS          // 下一步行动
    }

    /**
     * A fact embedded into the conversation that should survive compaction.
     *
     * <p>Two granularities of matching are supported:
     * <ul>
     *   <li>{@code needle} — the full sentence, matched verbatim. Strict: a real
     *       (paraphrasing) summarizer almost never reproduces it, so this is a
     *       lower bound.</li>
     *   <li>{@code tokens} — the atomic, verbatim-quotable atoms the summary
     *       prompt promises to preserve (file paths, identifiers, error strings,
     *       constraint keywords). Token-overlap recall tolerates rewording of the
     *       surrounding prose, giving a far more meaningful real-LLM number.</li>
     * </ul>
     *
     * @param category   which of the 7 buckets it belongs to
     * @param needle     full distinctive string matched verbatim (whitespace-normalized)
     * @param tokens     atomic verbatim-quotable tokens for tolerant matching
     * @param turnIndex  the conversation turn it was embedded in (for diagnostics)
     */
    record GoldFact(Category category, String needle, List<String> tokens, int turnIndex) {}

    // ── Recall computation ────────────────────────────────────────────

    /** True if the needle survives in the haystack (whitespace-normalized substring). */
    static boolean recalled(String needle, String haystack) {
        return normalize(haystack).contains(normalize(needle));
    }

    /** Overall recall: fraction of gold facts whose needle survives. */
    static double overallRecall(List<GoldFact> facts, String haystack) {
        if (facts.isEmpty()) return 1.0;
        String norm = normalize(haystack);
        int hits = 0;
        for (GoldFact f : facts) {
            if (norm.contains(normalize(f.needle()))) hits++;
        }
        return (double) hits / facts.size();
    }

    /**
     * Per-category exact recall. Each entry is {@code int[]{hits, total}} so
     * callers can both report a ratio and see which buckets lose the most.
     */
    static Map<Category, int[]> recallByCategory(List<GoldFact> facts, String haystack) {
        String norm = normalize(haystack);
        Map<Category, int[]> out = new EnumMap<>(Category.class);
        for (GoldFact f : facts) {
            int[] tally = out.computeIfAbsent(f.category(), k -> new int[2]);
            tally[1]++; // total
            if (norm.contains(normalize(f.needle()))) tally[0]++; // hit
        }
        return out;
    }

    // ── Token-overlap (tolerant) recall ───────────────────────────────

    /**
     * Fraction of a fact's atomic tokens present in the haystack (case- and
     * whitespace-insensitive). Tolerates paraphrasing of the surrounding prose.
     * A fact with no tokens falls back to exact needle match (1.0 / 0.0).
     */
    static double tokenRecall(GoldFact fact, String haystack) {
        String norm = normalizeLower(haystack);
        List<String> tokens = fact.tokens();
        if (tokens == null || tokens.isEmpty()) {
            return recalled(fact.needle(), haystack) ? 1.0 : 0.0;
        }
        int hits = 0;
        for (String t : tokens) {
            if (norm.contains(normalizeLower(t))) hits++;
        }
        return (double) hits / tokens.size();
    }

    /** Mean token-overlap recall across all facts. */
    static double avgTokenRecall(List<GoldFact> facts, String haystack) {
        if (facts.isEmpty()) return 1.0;
        double sum = 0;
        for (GoldFact f : facts) sum += tokenRecall(f, haystack);
        return sum / facts.size();
    }

    /** Per-category mean token-overlap recall as {@code double[]{sumRecall, count}}. */
    static Map<Category, double[]> tokenRecallByCategory(List<GoldFact> facts, String haystack) {
        Map<Category, double[]> out = new EnumMap<>(Category.class);
        for (GoldFact f : facts) {
            double[] acc = out.computeIfAbsent(f.category(), k -> new double[2]);
            acc[0] += tokenRecall(f, haystack);
            acc[1] += 1;
        }
        return out;
    }

    // ── Context serialization ─────────────────────────────────────────

    /**
     * Flatten a conversation into one searchable string: message text, tool
     * call names + arguments, tool result bodies, and thinking blocks. Mirrors
     * what actually occupies the context window after compaction.
     */
    static String serializeContext(ConversationManager conv) {
        var sb = new StringBuilder(4096);
        for (Message m : conv.getMessages()) {
            sb.append('[').append(m.getRole()).append("] ");
            if (m.getContent() != null) sb.append(m.getContent()).append('\n');

            if (m.getThinkingBlocks() != null) {
                for (ThinkingBlock tb : m.getThinkingBlocks()) {
                    if (tb.thinking() != null) sb.append(tb.thinking()).append('\n');
                }
            }
            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    sb.append("tool:").append(tu.toolName());
                    if (tu.arguments() != null) sb.append(' ').append(tu.arguments());
                    sb.append('\n');
                }
            }
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    if (tr.content() != null) sb.append(tr.content()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Collapse all whitespace runs to a single space so layout differences don't break matching. */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Like {@link #normalize} but also lowercased, for tolerant token matching. */
    private static String normalizeLower(String s) {
        return normalize(s).toLowerCase();
    }
}
