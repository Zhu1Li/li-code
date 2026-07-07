package com.licode.tool.impl;

import java.util.Set;

public final class ToolConstants {
    private ToolConstants() {}

    public static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".venv", "node_modules", "__pycache__", ".tox", ".mypy_cache"
    );
}
