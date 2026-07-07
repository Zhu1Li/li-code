package com.licode.team;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MailMessage(
        String from,
        String to,
        String text,
        String timestamp,
        boolean read,
        String color,
        String summary,
        ProtocolMessageType type
) {
    public enum ProtocolMessageType {
        IDLE,
        SHUTDOWN,
        APPROVAL_REQUEST,
        APPROVAL_RESPONSE,
        PROGRESS,
        ERROR,
        HANDOFF
    }

    public MailMessage(String from, String to, String text) {
        this(from, to, text, Instant.now().toString(), false, null, null, null);
    }

    public MailMessage withRead(boolean read) {
        return new MailMessage(from, to, text, timestamp, read, color, summary, type);
    }

    public boolean isIdle() { return type == ProtocolMessageType.IDLE; }
    public boolean isShutdown() { return type == ProtocolMessageType.SHUTDOWN; }
    public boolean isApprovalRequest() { return type == ProtocolMessageType.APPROVAL_REQUEST; }
    public boolean isApprovalResponse() { return type == ProtocolMessageType.APPROVAL_RESPONSE; }
    public boolean isProgress() { return type == ProtocolMessageType.PROGRESS; }
    public boolean isError() { return type == ProtocolMessageType.ERROR; }
    public boolean isHandoff() { return type == ProtocolMessageType.HANDOFF; }

    public static MailMessage idle(String from, String reason) {
        return new MailMessage(from, "lead",
                "[idle] " + from + ": " + reason,
                Instant.now().toString(), false, null, reason, ProtocolMessageType.IDLE);
    }

    public static MailMessage shutdown(String reason) {
        return new MailMessage("lead", null,
                "[shutdown] " + reason,
                Instant.now().toString(), false, null, null, ProtocolMessageType.SHUTDOWN);
    }

    public static MailMessage approvalRequest(String from, String toolName, String description) {
        return new MailMessage(from, "lead",
                "[approval-request] " + from + ": " + toolName + ": " + description,
                Instant.now().toString(), false, null, null, ProtocolMessageType.APPROVAL_REQUEST);
    }

    public static MailMessage approvalResponse(String to, boolean approved, String rationale) {
        return new MailMessage("lead", to,
                "[approval-response] " + (approved ? "approved" : "denied") + ": " + rationale,
                Instant.now().toString(), false, null, null, ProtocolMessageType.APPROVAL_RESPONSE);
    }

    public static MailMessage progress(String from, int toolCount, long tokenCount, String status) {
        return new MailMessage(from, "lead",
                "[progress] " + from + ": tools=" + toolCount + " tokens=" + tokenCount + " status=" + status,
                Instant.now().toString(), false, null, null, ProtocolMessageType.PROGRESS);
    }

    public static MailMessage error(String from, String errorMessage) {
        return new MailMessage(from, "lead",
                "[error] " + from + ": " + errorMessage,
                Instant.now().toString(), false, null, null, ProtocolMessageType.ERROR);
    }

    public static MailMessage handoff(String from, String to, String taskDescription) {
        return new MailMessage(from, to,
                "[handoff] " + from + " -> " + to + ": " + taskDescription,
                Instant.now().toString(), false, null, null, ProtocolMessageType.HANDOFF);
    }
}
