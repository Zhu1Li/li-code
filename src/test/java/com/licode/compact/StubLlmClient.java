package com.licode.compact;

import com.licode.conversation.ConversationManager;
import com.licode.llm.LlmClient;
import com.licode.llm.StreamEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

/**
 * Deterministic, offline {@link LlmClient} for compaction tests.
 *
 * <p>{@link ContextCompactor#autoCompact} asks the client to summarize a single
 * user message (the summary prompt + serialized transcript) and reads
 * {@code TextDelta} events until {@code StreamEnd}. This stub maps that prompt
 * to a fixed summary via the supplied function, so recall tests run without a
 * network call and produce identical results every time.
 */
final class StubLlmClient implements LlmClient {

    private final Function<String, String> summarizer;

    private StubLlmClient(Function<String, String> summarizer) {
        this.summarizer = summarizer;
    }

    /**
     * Perfect-recall summarizer: echoes the serialized transcript back inside
     * {@code <summary>} tags, so every fact in the summarized prefix survives.
     * Used to validate that the recall extractor itself works.
     *
     * <p>The prompt the compactor sends is {@code LI_CODE_SUMMARY_PROMPT + "\n\n"
     * + transcript}, and that prompt text itself contains literal {@code
     * <summary>}/{@code </summary>} tags (in its instructions). We must strip the
     * prompt prefix first — otherwise {@code formatCompactSummary} would extract
     * the prompt's example placeholder instead of the real transcript.
     */
    static StubLlmClient faithful() {
        return new StubLlmClient(prompt -> {
            String body = prompt;
            String p = ContextCompactor.LI_CODE_SUMMARY_PROMPT;
            int i = body.indexOf(p);
            if (i >= 0) body = body.substring(i + p.length());
            return "<summary>" + body + "</summary>";
        });
    }

    /**
     * Lossy summarizer: returns a fixed generic blurb that drops every fact.
     * Used to confirm the recall metric actually detects information loss.
     */
    static StubLlmClient lossy() {
        return new StubLlmClient(prompt ->
                "<summary>\n会话已压缩，具体细节从略。\n</summary>");
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
        String prompt = conv.getMessages().isEmpty() ? "" : conv.getMessages().get(0).getContent();
        String summary = summarizer.apply(prompt == null ? "" : prompt);
        queue.offer(new StreamEvent.TextDelta(summary));
        queue.offer(new StreamEvent.StreamEnd("end_turn", 0, 0));
        return queue;
    }

    @Override
    public void cancelStream() {
        // no-op: nothing is in flight
    }
}
