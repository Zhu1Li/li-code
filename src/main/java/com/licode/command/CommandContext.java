package com.licode.command;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public record CommandContext(
        String args,
        String workDir,
        Supplier<String> model,
        Supplier<String> permissionMode,
        IntSupplier toolCount,
        IntSupplier totalInputTokens,
        IntSupplier totalOutputTokens,
        Supplier<List<String>> memoryList,
        Runnable memoryClear,
        Supplier<String> sessionInfo,
        Supplier<List<String>> skillList,
        Runnable clearChat,
        Runnable triggerCompact,
        Runnable switchToPlanMode,
        Runnable switchToDefaultMode,
        Runnable enterResumeState) {
}
