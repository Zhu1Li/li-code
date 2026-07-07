package com.licode.team;

import java.time.Instant;

public class TeammateProgress {

    public enum Status { RUNNING, IDLE, COMPLETED, FAILED, STOPPED }

    private final String teamName;
    private final String memberName;
    private volatile Status status = Status.RUNNING;
    private volatile int toolUseCount;
    private volatile long tokenCount;
    private volatile long inputTokens;
    private volatile long outputTokens;
    private volatile String lastActivity = "";
    private volatile String spinnerVerb = "Working";

    public TeammateProgress(String teamName, String memberName) {
        this.teamName = teamName;
        this.memberName = memberName;
    }

    public String teamName() { return teamName; }
    public String memberName() { return memberName; }
    public Status status() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int toolUseCount() { return toolUseCount; }
    public void incrementToolCount() { toolUseCount++; }
    public long tokenCount() { return tokenCount; }
    public void addTokens(long n) { tokenCount += n; }
    public long inputTokens() { return inputTokens; }
    public void setInputTokens(long t) { inputTokens = t; }
    public long outputTokens() { return outputTokens; }
    public void setOutputTokens(long t) { outputTokens = t; }
    public String lastActivity() { return lastActivity; }
    public void setLastActivity(String a) { lastActivity = a; }
    public String spinnerVerb() { return spinnerVerb; }
    public void setSpinnerVerb(String v) { spinnerVerb = v; }
}
