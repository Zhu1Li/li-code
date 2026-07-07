package com.licode.worktree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorktreeSession(
        @JsonProperty("original_cwd") String originalCwd,
        @JsonProperty("worktree_path") String worktreePath,
        @JsonProperty("worktree_name") String worktreeName,
        @JsonProperty("worktree_branch") String worktreeBranch,
        @JsonProperty("original_branch") String originalBranch,
        @JsonProperty("session_id") String sessionId
) {}
