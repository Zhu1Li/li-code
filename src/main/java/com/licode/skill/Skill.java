package com.licode.skill;

import java.nio.file.Path;

public record Skill(
        SkillMeta meta,
        String promptBody,
        Path sourceDir,
        boolean isDirectory,
        boolean bodyLoaded) {

    public Skill {
        if (meta == null) throw new IllegalArgumentException("meta is required");
        if (promptBody == null) promptBody = "";
    }

    public Skill withBody(String newBody) {
        return new Skill(meta, newBody, sourceDir, isDirectory, true);
    }

    public Skill withBodyLoaded(boolean loaded) {
        return new Skill(meta, promptBody, sourceDir, isDirectory, loaded);
    }

    public String render(String args) {
        String body = promptBody;
        if (body.contains("$ARGUMENTS")) {
            return body.replace("$ARGUMENTS", args != null ? args : "");
        }
        if (args == null || args.isBlank()) {
            return body;
        }
        return body + "\n\n## User Request\n\n" + args;
    }
}
