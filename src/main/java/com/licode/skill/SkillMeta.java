package com.licode.skill;

import java.util.List;

public record SkillMeta(
        String name,
        String description,
        String whenToUse,
        List<String> tags,
        List<String> allowedTools,
        String mode,
        String model,
        String forkContext) {

    public SkillMeta {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (description == null) description = "";
        if (whenToUse == null) whenToUse = "";
        if (tags == null) tags = List.of();
        if (allowedTools == null) allowedTools = List.of();
        if (mode == null || mode.isBlank()) mode = "inline";
        if (model == null || model.isBlank()) model = "";
        if (forkContext == null || forkContext.isBlank()) forkContext = "none";
    }

    public boolean isFork() {
        return "fork".equals(mode);
    }
}
