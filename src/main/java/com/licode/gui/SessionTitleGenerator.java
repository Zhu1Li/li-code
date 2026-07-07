package com.licode.gui;

import com.licode.config.ProviderConfig;
import com.licode.conversation.ConversationManager;
import com.licode.llm.LlmClient;
import com.licode.llm.StreamEvent;
import javafx.application.Platform;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * After the first round of a session completes, sends the user question
 * and a truncated assistant response to a dedicated LLM call to generate
 * a short session title.
 */
class SessionTitleGenerator {

    private static final String SYSTEM_PROMPT =
            "Generate a concise title (maximum 6 words, in English) that summarizes "
                    + "the user's question and the assistant's task. "
                    + "Return ONLY the title text, no quotes, no explanation.";

    private final ProviderConfig providerConfig;

    SessionTitleGenerator(ProviderConfig providerConfig) {
        this.providerConfig = providerConfig;
    }

    /**
     * Asynchronously generates a title and calls onComplete on the JavaFX thread.
     */
    void generate(String sessionId, String userQuestion, String assistantSummary,
                  SessionTitleStore store, Runnable onComplete) {
        Thread.startVirtualThread(() -> {
            try {
                String title = generateTitleSync(userQuestion, assistantSummary);
                if (title != null && !title.isBlank()) {
                    store.save(sessionId, title.trim());
                    Platform.runLater(onComplete);
                }
            } catch (Exception ignored) {
                // Title generation is best-effort; never block the UI
            }
        });
    }

    private String generateTitleSync(String question, String summary) throws Exception {
        var client = LlmClient.create(providerConfig, SYSTEM_PROMPT);
        var conv = new ConversationManager();
        conv.addUserMessage("User: " + truncate(question, 200) + "\n\nAssistant: " + truncate(summary, 300));

        BlockingQueue<StreamEvent> queue = client.stream(conv, List.of());

        StringBuilder text = new StringBuilder();
        while (true) {
            StreamEvent event = queue.poll(120, TimeUnit.SECONDS);
            if (event == null) break;
            if (event instanceof StreamEvent.TextDelta delta) {
                text.append(delta.text());
            } else if (event instanceof StreamEvent.StreamEnd) {
                break;
            } else if (event instanceof StreamEvent.Error) {
                return null;
            }
        }
        return text.toString().replace("\"", "").replace("'", "").strip();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
