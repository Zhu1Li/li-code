package com.licode.toolresult;

import com.licode.conversation.ConversationManager;

import java.util.List;

/**
 * Result of {@link ToolResultBudget#apply}: a freshly-built
 * {@link ConversationManager} with replacements applied (the input conv is
 * never mutated — that's the whole point of Design B) plus the list of
 * decisions newly made on this call (subset of {@code state.replacements}
 * additions), which the caller should append to the session transcript so
 * resume can rebuild state.
 */
public record ApplyResult(ConversationManager apiConv, List<ContentReplacementRecord> newRecords) {}
