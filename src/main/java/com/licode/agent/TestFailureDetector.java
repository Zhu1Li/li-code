package com.licode.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort 识别命令输出里"测试失败"的信号，用于提示用户可以 `/fix-tests`。
 * 纯函数、可单测；**宁可漏报不可误报**——不确定就返回 false。
 */
public final class TestFailureDetector {

    private TestFailureDetector() {}

    // Maven surefire 汇总：Tests run: N, Failures: F, Errors: E
    private static final Pattern SUREFIRE =
            Pattern.compile("Tests run:\\s*\\d+,\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+)");
    // pytest / jest 风格：N failed
    private static final Pattern N_FAILED = Pattern.compile("\\b(\\d+)\\s+failed\\b");
    // go test：行首 --- FAIL:
    private static final Pattern GO_FAIL = Pattern.compile("(?m)^--- FAIL:");

    /** 输出看起来像"测试失败"就返回 true。成功输出（BUILD SUCCESS / Failures: 0）不会命中。 */
    public static boolean looksLikeTestFailure(String output) {
        if (output == null || output.isBlank()) return false;

        // Maven：失败数或错误数 > 0
        Matcher sm = SUREFIRE.matcher(output);
        while (sm.find()) {
            if (Integer.parseInt(sm.group(1)) > 0 || Integer.parseInt(sm.group(2)) > 0) return true;
        }

        if (output.contains("BUILD FAILURE") || output.contains("BUILD FAILED")) return true;

        // pytest/jest：真的有 >0 个 failed（排除 "0 failed"）
        Matcher fm = N_FAILED.matcher(output);
        while (fm.find()) {
            if (Integer.parseInt(fm.group(1)) > 0) return true;
        }

        return GO_FAIL.matcher(output).find();
    }

    /** 给用户看的提示文案。 */
    public static String hint() {
        return "Tests appear to be failing — run /fix-tests to auto-repair them in an isolated sub-agent.";
    }
}
