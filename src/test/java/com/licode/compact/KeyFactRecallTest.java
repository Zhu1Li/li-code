package com.licode.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.licode.compact.BenchmarkContent.GoldConversation;
import com.licode.compact.KeyFactRecall.Category;
import com.licode.compact.KeyFactRecall.GoldFact;
import com.licode.config.AppConfig;
import com.licode.config.ConfigLoader;
import com.licode.config.ProviderConfig;
import com.licode.llm.LlmClient;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Key-fact recall metric for context compaction.
 *
 * <p>Compression ratio measures shrinkage; this measures fidelity — whether the
 * facts that matter survive compaction. The deterministic tests use a stub
 * summarizer (no network) to prove both that the metric reports high recall for
 * a faithful summary and that it detects loss for a lossy one. An opt-in test
 * runs the real LLM and emits a per-category artifact.
 *
 * <p>Run deterministic: {@code mvn test -Dtest=KeyFactRecallTest}
 * <p>Run with real LLM: {@code mvn test -Dtest=KeyFactRecallTest -Dlicode.benchmark.llm=1}
 */
class KeyFactRecallTest {

    private static final int CONTEXT_WINDOW = 200_000;
    private static final int TURNS = 16;
    private static final int RESULT_CHARS = 4_000;
    private static final String RECOVER_NEEDLE = "恢复标记 RECOVER-NEEDLE-7c1d：扣减表 tb_voucher_stock 的乐观锁版本字段 revision";

    @Test
    void faithfulSummaryRetainsFactsLossyOneDrops() {
        GoldConversation gc = BenchmarkContent.buildConversationWithGoldFacts(TURNS, RESULT_CHARS);
        List<GoldFact> prefixFacts = gc.facts().stream().filter(f -> f.turnIndex() <= 2).toList();
        GoldFact tailFact = gc.facts().stream()
                .filter(f -> f.turnIndex() == TURNS - 1).findFirst().orElseThrow();

        // ── Faithful summary + recovery attachment ──
        RecoveryState rec = new RecoveryState();
        rec.recordFileRead("notes/decisions.md",
                "## 扣减表结构\n" + RECOVER_NEEDLE + "\n详见迁移脚本。\n");

        ContextCompactor.forceCompact(gc.conv(), StubLlmClient.faithful(), CONTEXT_WINDOW, null, rec);
        String faithfulHay = KeyFactRecall.serializeContext(gc.conv());
        double faithfulRecall = KeyFactRecall.overallRecall(prefixFacts, faithfulHay);

        assertTrue(faithfulRecall >= 0.9,
                "faithful summary should retain ≥90% of prefix facts, got " + faithfulRecall);
        assertTrue(faithfulHay.contains("notes/decisions.md"),
                "RecoveryState should list the file path as an index reference");
        assertTrue(KeyFactRecall.recalled(tailFact.needle(), faithfulHay),
                "tail fact should survive in the kept verbatim tail");

        // ── Lossy summary (fresh conversation, since forceCompact mutates) ──
        GoldConversation gc2 = BenchmarkContent.buildConversationWithGoldFacts(TURNS, RESULT_CHARS);
        ContextCompactor.forceCompact(gc2.conv(), StubLlmClient.lossy(), CONTEXT_WINDOW,
                null, new RecoveryState());
        String lossyHay = KeyFactRecall.serializeContext(gc2.conv());
        double lossyRecall = KeyFactRecall.overallRecall(prefixFacts, lossyHay);

        assertTrue(lossyRecall < faithfulRecall - 0.4,
                "lossy summary recall (" + lossyRecall + ") should be far below faithful ("
                        + faithfulRecall + ") — the metric must detect information loss");
        assertTrue(lossyRecall < 0.3,
                "lossy summary should drop most prefix facts, got " + lossyRecall);
        assertTrue(KeyFactRecall.recalled(tailFact.needle(), lossyHay),
                "tail fact must survive even a lossy summary — it lives in the kept tail, not the summary");

        // ── Token-overlap (tolerant) recall must track the same signal ──
        double faithfulTok = KeyFactRecall.avgTokenRecall(prefixFacts, faithfulHay);
        double lossyTok = KeyFactRecall.avgTokenRecall(prefixFacts, lossyHay);
        assertTrue(faithfulTok >= 0.9,
                "faithful token-overlap recall should be ≥0.9, got " + faithfulTok);
        assertTrue(lossyTok < faithfulTok - 0.4,
                "lossy token recall (" + lossyTok + ") should be far below faithful (" + faithfulTok + ")");
    }

    /** Opt-in: run the real configured LLM, measure per-category recall, write artifact. */
    @Test
    void realLlmRecallArtifact() throws Exception {
        assumeTrue(System.getProperty("licode.benchmark.llm") != null,
                "set -Dlicode.benchmark.llm=1 to run the real-LLM recall benchmark");

        ProviderConfig provider = loadProviderOrSkip();
        LlmClient client = LlmClient.create(provider, "");

        GoldConversation gc = BenchmarkContent.buildConversationWithGoldFacts(TURNS, RESULT_CHARS);
        List<GoldFact> facts = gc.facts();

        ContextCompactor.forceCompact(gc.conv(), client, CONTEXT_WINDOW, null, new RecoveryState());
        String hay = KeyFactRecall.serializeContext(gc.conv());

        Map<Category, int[]> exactByCat = KeyFactRecall.recallByCategory(facts, hay);
        Map<Category, double[]> tokByCat = KeyFactRecall.tokenRecallByCategory(facts, hay);
        double exactOverall = KeyFactRecall.overallRecall(facts, hay);
        double tokOverall = KeyFactRecall.avgTokenRecall(facts, hay);

        System.out.printf("%n═══ Real-LLM key-fact recall (%s) ═══%n", provider.getModel());
        System.out.printf("  %-16s %8s  %8s%n", "category", "exact", "token");
        for (Category c : Category.values()) {
            int[] e = exactByCat.getOrDefault(c, new int[2]);
            double[] t = tokByCat.getOrDefault(c, new double[2]);
            double tok = t[1] > 0 ? t[0] / t[1] : 1.0;
            System.out.printf("  %-16s   %d/%d     %5.0f%%%n", c, e[0], e[1], tok * 100);
        }
        System.out.printf("  %-16s %7.1f%%  %7.1f%%%n", "OVERALL", exactOverall * 100, tokOverall * 100);

        writeArtifact(provider.getModel(), exactByCat, tokByCat, exactOverall, tokOverall);
    }

    private ProviderConfig loadProviderOrSkip() {
        try {
            AppConfig cfg = ConfigLoader.load();
            assumeTrue(cfg.getProviders() != null && !cfg.getProviders().isEmpty(),
                    "no provider configured");
            return cfg.getProviders().get(0);
        } catch (Exception e) {
            assumeTrue(false, "config load failed: " + e.getMessage());
            return null; // unreachable
        }
    }

    private void writeArtifact(String model, Map<Category, int[]> exactByCat,
                               Map<Category, double[]> tokByCat,
                               double exactOverall, double tokOverall) throws Exception {
        var cats = new LinkedHashMap<String, Object>();
        for (Category c : Category.values()) {
            int[] e = exactByCat.getOrDefault(c, new int[2]);
            double[] t = tokByCat.getOrDefault(c, new double[2]);
            cats.put(c.name(), Map.of(
                    "exact_hits", e[0], "total", e[1],
                    "exact_recall", e[1] > 0 ? (double) e[0] / e[1] : 1.0,
                    "token_recall", t[1] > 0 ? t[0] / t[1] : 1.0));
        }
        var artifact = new LinkedHashMap<String, Object>();
        artifact.put("schema_version", 2);
        artifact.put("artifact_type", "key-fact-recall");
        artifact.put("model", model);
        artifact.put("exact_overall_recall", exactOverall);
        artifact.put("token_overall_recall", tokOverall);
        artifact.put("by_category", cats);

        Path out = Path.of("target", "key-fact-recall.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(artifact));
        System.out.println("[benchmark] Artifact → " + out.toAbsolutePath());
    }
}
