package com.licode.agent;

import com.licode.conversation.Message;

import java.util.List;

/**
 * 目标漂移检查点：长任务里每隔若干轮，把**最初的任务原文**重新贴回上下文，让模型自检
 * "我下一步还在解这个吗"，缓解 goal drift（越做越偏、被中间发现带跑偏）。
 *
 * <p>三大 coding-agent 难点里，上下文失效靠压缩、知识不可复用靠记忆/失败库都已压住；
 * 这里补的是**目标漂移**——打转检测只防"反复失败重试"，不防"越做越偏"。
 *
 * <p>纯函数、无状态：{@link Agent} 在主循环里调用，注入沿用 plan-mode 的
 * {@code addSystemReminder}（只追加尾部、不改历史、不击穿 prompt 缓存前缀）。
 */
public final class GoalCheckpoint {

    /** 每隔多少轮工具迭代重锚一次。短于一个间隔的任务不触发（无漂移风险、不打扰）。 */
    static final int CHECK_INTERVAL = 10;

    /** 目标原文注入时的截断上限，避免超长目标撑大每次注入。 */
    static final int MAX_GOAL_CHARS = 600;

    private GoalCheckpoint() {}

    /** 轮次触发判定：第 CHECK_INTERVAL、2×、3×… 轮触发。 */
    public static boolean shouldCheck(int iteration) {
        return iteration > 0 && iteration % CHECK_INTERVAL == 0;
    }

    /**
     * 捕获"当前任务目标"：从后往前找第一条 role=user、内容非空、且不是注入的
     * system-reminder（也天然排除了内容为空的工具结果消息）。取不到返回 null。
     */
    public static String captureGoal(List<Message> messages) {
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (!"user".equals(m.getRole())) continue;
            String c = m.getContent();
            if (c == null || c.isBlank()) continue;
            if (c.stripLeading().startsWith("<system-reminder>")) continue;
            String goal = c.strip();
            if (goal.length() > MAX_GOAL_CHARS) {
                goal = goal.substring(0, MAX_GOAL_CHARS).strip() + " …";
            }
            return goal;
        }
        return null;
    }

    /** 构建重锚提示：复述原始目标 + 要求模型自检一致性、跑偏则重新对齐。 */
    public static String buildReminder(String goal, int iteration) {
        return "<goal-check>\n"
                + "You are " + iteration + " tool-iterations into this task. The original request was:\n\""
                + goal + "\"\n"
                + "Before your next action, confirm it still directly serves that request. If you have "
                + "drifted onto a tangent or lost the thread, stop and re-align: restate the goal and the "
                + "remaining steps, then continue.\n"
                + "</goal-check>";
    }
}
