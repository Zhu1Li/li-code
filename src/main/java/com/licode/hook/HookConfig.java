package com.licode.hook;

import java.util.List;
import java.util.Map;

/**
 * YAML-deserializable POJO for hook configuration.
 * Field names use snake_case in YAML → camelCase via ConfigLoader's SnakeCasePropertyUtils.
 */
public class HookConfig {

    private String id;
    private String event;
    private ConditionConfig condition;
    private ActionConfig action;
    private boolean reject;
    private boolean once;
    private boolean async;
    private int timeout; // seconds, 0 = default

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public ConditionConfig getCondition() { return condition; }
    public void setCondition(ConditionConfig condition) { this.condition = condition; }

    public ActionConfig getAction() { return action; }
    public void setAction(ActionConfig action) { this.action = action; }

    public boolean isReject() { return reject; }
    public void setReject(boolean reject) { this.reject = reject; }

    public boolean isOnce() { return once; }
    public void setOnce(boolean once) { this.once = once; }

    public boolean isAsync() { return async; }
    public void setAsync(boolean async) { this.async = async; }

    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }

    // ---- Nested config classes for YAML deserialization ----

    public static class ConditionConfig {
        private String mode; // "all" or "any"
        private List<RuleConfig> rules;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public List<RuleConfig> getRules() { return rules; }
        public void setRules(List<RuleConfig> rules) { this.rules = rules; }
    }

    public static class RuleConfig {
        private String variable;
        private String operator;
        private String value;

        public String getVariable() { return variable; }
        public void setVariable(String variable) { this.variable = variable; }

        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    public static class ActionConfig {
        private String type;
        private String command;
        private String message;
        private String url;
        private String method;
        private Map<String, String> headers;
        private String body;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }

        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
    }

    // ---- Conversion to HookEngine.Hook ----

    static HookEngine.Hook toHook(HookConfig cfg) {
        HookEngine.EventName event = parseEventName(cfg.getEvent());
        HookEngine.Action action = toAction(cfg.getAction());
        HookEngine.ConditionGroup condition = toConditionGroup(cfg.getCondition());
        return new HookEngine.Hook(
                cfg.getId() != null ? cfg.getId() : "",
                event,
                condition,
                action,
                cfg.isReject(),
                cfg.isOnce(),
                cfg.isAsync(),
                cfg.getTimeout()
        );
    }

    private static HookEngine.EventName parseEventName(String s) {
        if (s == null || s.isBlank()) return HookEngine.EventName.SESSION_START;
        return switch (s.strip().toLowerCase()) {
            case "startup"        -> HookEngine.EventName.STARTUP;
            case "shutdown"       -> HookEngine.EventName.SHUTDOWN;
            case "session_start"  -> HookEngine.EventName.SESSION_START;
            case "session_end"    -> HookEngine.EventName.SESSION_END;
            case "turn_start"     -> HookEngine.EventName.TURN_START;
            case "turn_end"       -> HookEngine.EventName.TURN_END;
            case "pre_send"       -> HookEngine.EventName.PRE_SEND;
            case "post_receive"   -> HookEngine.EventName.POST_RECEIVE;
            case "pre_tool_use"   -> HookEngine.EventName.PRE_TOOL_USE;
            case "post_tool_use"  -> HookEngine.EventName.POST_TOOL_USE;
            case "error"          -> HookEngine.EventName.ERROR;
            case "compact"        -> HookEngine.EventName.COMPACT;
            default               -> HookEngine.EventName.SESSION_START;
        };
    }

    private static HookEngine.Action toAction(ActionConfig a) {
        if (a == null) return new HookEngine.Action(HookEngine.ActionType.COMMAND, "", "", null, null, null, null, 0);
        HookEngine.ActionType type = parseActionType(a.getType());
        return new HookEngine.Action(
                type,
                a.getCommand(),
                a.getMessage(),
                a.getUrl(),
                a.getMethod(),
                a.getHeaders(),
                a.getBody(),
                0
        );
    }

    private static HookEngine.ActionType parseActionType(String s) {
        if (s == null || s.isBlank()) return HookEngine.ActionType.COMMAND;
        return switch (s.strip().toLowerCase()) {
            case "command" -> HookEngine.ActionType.COMMAND;
            case "prompt"  -> HookEngine.ActionType.PROMPT;
            case "http"    -> HookEngine.ActionType.HTTP;
            case "agent"   -> HookEngine.ActionType.AGENT;
            default        -> HookEngine.ActionType.COMMAND;
        };
    }

    private static HookEngine.ConditionGroup toConditionGroup(ConditionConfig cc) {
        if (cc == null || cc.getRules() == null || cc.getRules().isEmpty()) return null;
        List<HookEngine.Condition> leaves = cc.getRules().stream()
                .map(r -> new HookEngine.Condition(
                        r.getVariable() != null ? r.getVariable() : "",
                        r.getOperator() != null ? r.getOperator() : "==",
                        r.getValue() != null ? r.getValue() : ""))
                .toList();
        return new HookEngine.ConditionGroup(
                cc.getMode() != null ? cc.getMode() : "all",
                leaves
        );
    }

    public static List<HookEngine.Hook> toHooks(List<HookConfig> configs) {
        if (configs == null || configs.isEmpty()) return List.of();
        return configs.stream().map(HookConfig::toHook).toList();
    }
}
