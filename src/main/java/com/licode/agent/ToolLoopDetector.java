package com.licode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Task-scoped detector for "stagnation loops" — the model calling the same tool
 * with identical arguments and failing over and over, making no progress.
 *
 * <p>Complements {@code textLessIterations} in {@link Agent}: that guard only
 * catches turns with <em>no</em> text/thinking (a broken tool-result channel),
 * so a model that narrates every turn while re-issuing the same failing call
 * slips through. This detector keys on the (tool name + canonical args)
 * signature of <em>failed</em> calls only.
 *
 * <p>Design:
 * <ul>
 *   <li>Only {@code isError == true} calls are counted (successful repeated
 *       reads are not a loop and must not be flagged).</li>
 *   <li>A bounded sliding window of the most recent failed signatures is kept;
 *       a signature reaching {@link #TRIP_THRESHOLD} occurrences <em>within the
 *       window</em> trips — counting occurrences (not "consecutive identical")
 *       so oscillating loops like {@code A B A B A} are caught.</li>
 *   <li>First trip → {@link Verdict#NUDGE}: caller injects a warning reminder,
 *       and the signature's window occupancy is cleared so it needs to
 *       re-accumulate before firing again (no per-turn nagging).</li>
 *   <li>An already-nudged signature tripping again → {@link Verdict#ABORT}.</li>
 * </ul>
 *
 * <p>Not thread-safe: a detector belongs to one {@link Agent} instance and is
 * only touched from that agent's single loop thread.
 */
public final class ToolLoopDetector {

    /** Most recent failed signatures retained in the sliding window. */
    static final int LOOP_WINDOW = 10;
    /** Occurrences of one signature within the window that trigger intervention. */
    static final int TRIP_THRESHOLD = 3;
    /** How many nudges a signature gets before a repeat trip escalates to abort. */
    static final int ABORT_AFTER_NUDGES = 1;

    /** Three-valued verdict returned per recorded failure. */
    public enum Verdict { OK, NUDGE, ABORT }

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Deque<String> window = new ArrayDeque<>();
    private final Map<String, Integer> nudgeCounts = new HashMap<>();

    /**
     * 记录一次工具调用结果，返回是否需要干预（OK / NUDGE / ABORT）。
     *
     * <p>只统计失败调用：成功调用直接返回 OK、不入窗不计数（正常的重复读不算打转）。
     * 同一失败签名在滑动窗口内出现次数达到阈值时：首次触发 → NUDGE（调用方注入提示，
     * 并清掉该签名在窗口的占用，令其需重新累计）；已提示过仍再次达阈值 → ABORT。
     */
    public Verdict record(String toolName, Map<String, Object> args, boolean isError) {
        if (!isError) {
            return Verdict.OK;
        }

        String sig = signature(toolName, args);
        window.addLast(sig);
        while (window.size() > LOOP_WINDOW) {
            window.removeFirst();
        }

        long count = window.stream().filter(sig::equals).count();
        if (count < TRIP_THRESHOLD) {
            return Verdict.OK;
        }

        // Threshold reached. Abort once this signature has already used up its
        // allowed nudges and is still looping; otherwise nudge and clear its
        // window occupancy so it must re-accumulate before firing again.
        int priorNudges = nudgeCounts.getOrDefault(sig, 0);
        if (priorNudges >= ABORT_AFTER_NUDGES) {
            return Verdict.ABORT;
        }
        nudgeCounts.put(sig, priorNudges + 1);
        window.removeIf(sig::equals);
        return Verdict.NUDGE;
    }

    /** Builds the error message used when an already-nudged signature keeps looping. */
    public String buildAbort(String toolName) {
        return "Agent aborted: `" + toolName + "` failed with identical arguments repeatedly "
                + "even after a warning to change approach (suspected infinite loop).";
    }

    /** Builds the reminder text injected on a {@link Verdict#NUDGE}. */
    public String buildNudge(String toolName, int failCount) {
        return "<loop-warning>\n"
                + "You have called `" + toolName + "` with identical arguments and it failed "
                + failCount + " times. Stop repeating the same call — the same inputs will not "
                + "produce a different result. Either (a) change your arguments or approach, "
                + "(b) use a different tool, or (c) if you are blocked, stop and explain what is "
                + "wrong instead of retrying.\n"
                + "</loop-warning>";
    }

    /**
     * 计算调用签名：工具名 + 参数「按 key 递归排序」后的 JSON，取 SHA-256 前 16 位 hex。
     * 排序保证与参数书写顺序无关：{@code {"a":1,"b":2}} 与 {@code {"b":2,"a":1}} 得到同一签名。
     */
    static String signature(String toolName, Map<String, Object> args) {
        String canonicalArgs;
        try {
            canonicalArgs = CANONICAL_MAPPER.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception e) {
            canonicalArgs = String.valueOf(args);
        }
        String raw = toolName + " " + canonicalArgs;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
