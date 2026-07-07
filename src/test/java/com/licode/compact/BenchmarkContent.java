package com.licode.compact;

import com.licode.compact.KeyFactRecall.Category;
import com.licode.compact.KeyFactRecall.GoldFact;
import com.licode.conversation.ConversationManager;
import com.licode.conversation.ToolResultBlock;
import com.licode.conversation.ToolUseBlock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Realistic synthetic content for context-compaction benchmarks.
 *
 * <p>Replaces {@code "x".repeat(N)} with content whose character distribution
 * resembles real tool output (source code, CLI output, logs, grep results).
 * This matters for L2 estimation because token density (chars/3.5) and summary
 * compression depend on information structure, not just byte count.
 *
 * <h3>Content override</h3>
 * <p>Place real text files in {@code src/test/resources/benchmark/}:
 * <ul>
 *   <li>{@code source_file.txt}  — used for ReadFile tool results</li>
 *   <li>{@code cli_output.txt}   — used for Bash tool results</li>
 *   <li>{@code grep_output.txt}  — used for Grep tool results</li>
 * </ul>
 * When present, content is read from those files (repeated if needed to reach
 * target size). When absent, the built-in generators produce semi-realistic
 * synthetic content.
 */
final class BenchmarkContent {

    private static final Path RESOURCE_DIR = Path.of("src/test/resources/benchmark");
    private static final RandomGenerator RNG = RandomGenerator.getDefault();

    private BenchmarkContent() {}

    /**
     * Returns content for a tool result of the given type and target size.
     *
     * @param kind       tool type: "ReadFile", "Bash", "Grep"
     * @param targetChars desired approximate character count
     * @return content string at roughly targetChars length
     */
    static String forTool(String kind, int targetChars) {
        Path overrideFile = switch (kind) {
            case "ReadFile" -> RESOURCE_DIR.resolve("source_file.txt");
            case "Bash"    -> RESOURCE_DIR.resolve("cli_output.txt");
            case "Grep"    -> RESOURCE_DIR.resolve("grep_output.txt");
            default        -> null;
        };

        if (overrideFile != null && Files.exists(overrideFile)) {
            try {
                return repeatToSize(Files.readString(overrideFile), targetChars);
            } catch (Exception ignored) { /* fall through to synthetic */ }
        }

        return switch (kind) {
            case "ReadFile" -> generateSourceFile(targetChars);
            case "Bash"     -> generateCliOutput(targetChars);
            case "Grep"     -> generateGrepOutput(targetChars);
            default         -> generateGeneric(targetChars);
        };
    }

    /**
     * Returns user message content for turn {@code turnNum} of {@code totalTurns}.
     */
    static String userMessage(int turnNum, int totalTurns) {
        return switch (turnNum % 5) {
            case 0 -> "请分析 " + filePath(turnNum) + " 的代码结构，关注潜在的性能问题和安全漏洞。"
                    + "重点检查：线程安全、资源释放、异常处理、SQL 注入风险。";
            case 1 -> "上一步的分析结果请记录下来，然后运行相关的测试用例验证你的判断。"
                    + "如果测试失败，请读取失败日志分析原因。";
            case 2 -> "现在请跨文件搜索所有调用 " + methodName(turnNum)
                    + " 的地方，检查调用方是否正确处理了返回值和异常。"
                    + "把调用链整理成一个表格。";
            case 3 -> "前面发现的问题需要在以下文件中修复："
                    + filePath(turnNum) + "、" + filePath(turnNum + 1) + "、"
                    + filePath(turnNum + 2) + "。"
                    + "请在修改前先读取每个文件的完整内容，确认修改点。";
            default -> "继续推进第 " + turnNum + " 步任务。"
                    + "当前进度：已完成 " + (turnNum - 1) + " / " + totalTurns + " 步。"
                    + "请读取 " + filePath(turnNum) + " 了解当前代码状态后继续。";
        };
    }

    /**
     * Returns assistant thinking text for a turn.
     */
    static String assistantText(int turnNum) {
        return "Step " + turnNum + " 分析完成。"
                + "已检查 " + filePath(turnNum) + "，发现以下要点需要关注。"
                + "接下来继续执行剩余步骤。";
    }

    // ── Gold-fact conversation (for key-fact recall) ──────────────────

    /** A synthetic conversation plus the gold facts embedded into it. */
    record GoldConversation(ConversationManager conv, List<GoldFact> facts) {}

    /**
     * Builds a conversation with distinctive "gold facts" embedded into early
     * turns (so they land in the summarized prefix) plus one fact in the final
     * turn (so it lands in the verbatim kept tail). The needles are fixed
     * strings that do not occur in the synthetic filler, so substring matching
     * is unambiguous.
     *
     * @param turns       number of conversation turns (use >= ~12 so the prefix
     *                    actually gets summarized rather than fully kept)
     * @param resultChars approximate size of each tool result
     */
    static GoldConversation buildConversationWithGoldFacts(int turns, int resultChars) {
        ConversationManager conv = new ConversationManager();
        List<GoldFact> facts = new ArrayList<>();
        String readContent = forTool("ReadFile", resultChars);
        String bashContent = forTool("Bash", resultChars / 5);

        for (int i = 0; i < turns; i++) {
            String userText = userMessage(i, turns);
            String asstText = assistantText(i);

            // Prefix facts: turns 0–2 (summarized) — embedded in message *content*,
            // which serializeForSummary keeps uncapped (unlike tool results).
            if (i == 0) {
                userText += " " + embed(facts, Category.USER_REQUIREMENT, 0);
                userText += " " + embed(facts, Category.CURRENT_GOAL, 0);
                asstText += " " + embed(facts, Category.KEY_DECISION, 0);
            } else if (i == 1) {
                userText += " " + embed(facts, Category.FILES_CODE, 1);
                userText += " " + embed(facts, Category.ERRORS_FIXES, 1);
                asstText += " " + embed(facts, Category.PENDING, 1);
            } else if (i == 2) {
                userText += " " + embed(facts, Category.NEXT_STEPS, 2);
            }

            // Tail fact: final turn (kept verbatim regardless of summary quality).
            if (i == turns - 1) {
                String tail = TAIL_NEEDLE;
                asstText += " " + tail;
                facts.add(new GoldFact(Category.KEY_DECISION, tail, List.of("TAIL-NEEDLE-9f3a"), turns - 1));
            }

            conv.addUserMessage(userText);
            conv.addAssistantFull(asstText, null,
                    List.of(
                            new ToolUseBlock("tu_read_" + i, "ReadFile", Map.of("file_path", filePath(i))),
                            new ToolUseBlock("tu_bash_" + i, "Bash", Map.of("command", "wc -l " + filePath(i)))
                    ),
                    null);
            conv.addToolResultsMessage(List.of(
                    new ToolResultBlock("tu_read_" + i, readContent, false),
                    new ToolResultBlock("tu_bash_" + i, bashContent, false)
            ));
            conv.addAssistantMessage(asstText);
        }
        return new GoldConversation(conv, facts);
    }

    private static String embed(List<GoldFact> facts, Category cat, int turn) {
        String needle = goldFactText(cat);
        facts.add(new GoldFact(cat, needle, goldFactTokens(cat), turn));
        return needle;
    }

    /** Distinctive marker for the kept-tail recall check. */
    static final String TAIL_NEEDLE = "尾部标记 TAIL-NEEDLE-9f3a：本轮结论是扣减成功且已落库";

    /** Fixed, verbatim-matchable fact text per category (no synthetic-filler collisions). */
    static String goldFactText(Category cat) {
        return switch (cat) {
            case USER_REQUIREMENT -> "硬约束：只能修改 PaymentService，绝对不要改动 OrderRepository";
            case CURRENT_GOAL     -> "当前目标：让 seckill 秒杀接口在 200ms 内完成扣减并返回";
            case KEY_DECISION     -> "关键决策：采用 Redis Lua 脚本做原子扣减，放弃数据库悲观锁";
            case FILES_CODE       -> "涉及文件 src/main/java/com/acme/pay/PaymentGateway.java 的 reconcileLedgerEntry 方法";
            case ERRORS_FIXES     -> "报错 java.sql.SQLException: Lock wait timeout exceeded，根因是事务 tx-7731 未提交";
            case PENDING          -> "待办：补充 KafkaConsumer 的幂等校验逻辑";
            case NEXT_STEPS       -> "下一步：运行 PaymentServiceTest#shouldDeductStockAtomically 验证扣减";
        };
    }

    /**
     * Atomic, verbatim-quotable tokens per fact — the things the summary prompt
     * promises to keep verbatim (paths, identifiers, error strings, key terms).
     * Token-overlap recall over these tolerates prose rewording, so the metric
     * reflects real fidelity rather than penalizing every paraphrase.
     */
    static List<String> goldFactTokens(Category cat) {
        return switch (cat) {
            case USER_REQUIREMENT -> List.of("PaymentService", "OrderRepository");
            case CURRENT_GOAL     -> List.of("seckill", "200ms");
            case KEY_DECISION     -> List.of("Redis", "Lua", "悲观锁");
            case FILES_CODE       -> List.of("PaymentGateway.java", "reconcileLedgerEntry");
            case ERRORS_FIXES     -> List.of("Lock wait timeout exceeded", "tx-7731");
            case PENDING          -> List.of("KafkaConsumer", "幂等");
            case NEXT_STEPS       -> List.of("PaymentServiceTest", "shouldDeductStockAtomically");
        };
    }

    // ── Synthetic generators (used when no override files exist) ──────

    private static String generateSourceFile(int targetChars) {
        var sb = new StringBuilder(targetChars + 1024);
        sb.append("// File: ").append(filePath(RNG.nextInt(20))).append('\n');
        sb.append("// Generated benchmark content — character distribution mimics real Java source\n");
        sb.append("// Replace with src/test/resources/benchmark/source_file.txt for realistic results\n\n");
        String pkg = randomPackage();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import java.util.*;\n");
        sb.append("import java.util.concurrent.*;\n");
        sb.append("import java.util.stream.*;\n\n");

        sb.append(generateClassBody(targetChars - sb.length()));
        return sb.toString();
    }

    private static String generateClassBody(int target) {
        var sb = new StringBuilder(target + 512);
        sb.append("""
                /**
                 * Service implementation for benchmark data processing.
                 * Handles request validation, transformation, and persistence.
                 */
                @Service
                public class DataProcessingService {
                    private static final int MAX_BATCH_SIZE = 1000;
                    private static final Duration TIMEOUT = Duration.ofSeconds(30);

                    private final Repository repository;
                    private final CacheManager cache;
                    private final ExecutorService executor;

                    public DataProcessingService(Repository repo, CacheManager cache) {
                        this.repository = repo;
                        this.cache = cache;
                        this.executor = Executors.newFixedThreadPool(
                            Runtime.getRuntime().availableProcessors()
                        );
                    }

                """);

        while (sb.length() < target) {
            sb.append(generateMethod(RNG.nextInt(20)));
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String generateMethod(int seed) {
        String name = methodName(seed);
        String returnType = seed % 3 == 0 ? "List<DataRecord>" : seed % 3 == 1 ? "Optional<Result>" : "void";
        String params = seed % 2 == 0
                ? "String requestId, Map<String, Object> params"
                : "long userId, QueryFilter filter";

        var sb = new StringBuilder();
        sb.append("""

                    /**
                     * Processes the given request with validation and caching.
                     * @return processed result or empty on failure
                     */
                    public %s %s(%s) {
                        // Validate input parameters
                        if (requestId == null || requestId.isBlank()) {
                            log.warn("Invalid request: empty requestId");
                            return %s;
                        }

                        // Check cache first
                        String cacheKey = "proc:%s".formatted(requestId);
                        var cached = cache.get(cacheKey);
                        if (cached != null) {
                            log.debug("Cache hit for key: {}", cacheKey);
                            return cached;
                        }

                        try {
                            // Acquire distributed lock
                            if (!lockService.tryLock(cacheKey, TIMEOUT)) {
                                log.warn("Failed to acquire lock for: {}", cacheKey);
                                return %s;
                            }

                            // Batch query with pagination
                            var batch = repository.findBatch(params, MAX_BATCH_SIZE);
                            if (batch.isEmpty()) {
                                log.info("No records found for request: {}", requestId);
                                return %s;
                            }

                            // Process in parallel
                            var futures = batch.stream()
                                .map(record -> executor.submit(() -> transform(record, filter)))
                                .toList();

                            var results = futures.stream()
                                .map(f -> {
                                    try { return f.get(5, TimeUnit.SECONDS); }
                                    catch (Exception e) {
                                        log.error("Transform failed", e);
                                        return null;
                                    }
                                })
                                .filter(Objects::nonNull)
                                .toList();

                            // Cache result with TTL
                            cache.set(cacheKey, results, Duration.ofMinutes(5));
                            return results;

                        } catch (Exception e) {
                            log.error("Processing failed for request: {}", requestId, e);
                            metricsCollector.recordFailure("process", e.getClass().getSimpleName());
                            return %s;
                        } finally {
                            lockService.release(cacheKey);
                        }
                    }
                """.formatted(
                returnType, name, params,
                emptyFor(returnType), cacheKey(),
                emptyFor(returnType), emptyFor(returnType),
                emptyFor(returnType)
        ));
        return sb.toString();
    }

    private static String generateCliOutput(int targetChars) {
        var sb = new StringBuilder(targetChars + 512);
        sb.append("$ mvn clean test -Dtest=").append(randomTestClass()).append(" 2>&1\n");
        sb.append("[INFO] Scanning for projects...\n");
        sb.append("[INFO] Building benchmark-project 1.0-SNAPSHOT\n\n");

        long start = System.nanoTime();
        while (sb.length() < targetChars) {
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            sb.append(String.format("[%6dms] ", elapsed));
            sb.append(switch (RNG.nextInt(6)) {
                case 0 -> "DEBUG " + randomClass() + " - Processing request " + randomId() + "\n";
                case 1 -> "INFO  " + randomClass() + " - Batch completed: " + (100 + RNG.nextInt(900))
                        + " records in " + RNG.nextInt(500) + "ms\n";
                case 2 -> "WARN  " + randomClass() + " - Slow query detected (> "
                        + (1000 + RNG.nextInt(9000)) + "ms): " + randomSql() + "\n";
                case 3 -> "ERROR " + randomClass() + " - Connection timeout after "
                        + RNG.nextInt(30) + "s for host " + randomHost() + "\n";
                case 4 -> "  at " + randomClass() + "." + methodName(RNG.nextInt(20))
                        + "(" + filePath(RNG.nextInt(30)) + ":" + RNG.nextInt(500) + ")\n";
                default -> "Caused by: java.sql.SQLException: "
                        + randomErrorMsg() + "\n";
            });
        }

        sb.append("\n[INFO] Tests run: ").append(RNG.nextInt(50) + 20)
          .append(", Failures: ").append(RNG.nextInt(3))
          .append(", Errors: ").append(RNG.nextInt(2))
          .append(", Skipped: 0\n");
        sb.append("[INFO] BUILD SUCCESS\n");
        return sb.toString();
    }

    private static String generateGrepOutput(int targetChars) {
        var sb = new StringBuilder(targetChars + 256);
        sb.append("$ grep -rn '").append(randomGrepPattern()).append("' src/main/java\n\n");
        while (sb.length() < targetChars) {
            sb.append(filePath(RNG.nextInt(20))).append(":")
              .append(RNG.nextInt(500) + 1).append(":    ")
              .append(randomCodeLine()).append('\n');
        }
        return sb.toString();
    }

    private static String generateGeneric(int targetChars) {
        var sb = new StringBuilder(targetChars + 256);
        while (sb.length() < targetChars) {
            sb.append("Line ").append(sb.length() / 80).append(": ")
              .append(randomCodeLine()).append('\n');
        }
        return sb.toString();
    }

    // ── Content file helpers ──────────────────────────────────────────

    /**
     * Repeats a base text to approximately reach targetChars, preserving
     * the original's character distribution.
     */
    static String repeatToSize(String base, int targetChars) {
        if (base == null || base.isEmpty()) return "";
        if (base.length() >= targetChars) return base.substring(0, targetChars);
        var sb = new StringBuilder(targetChars);
        sb.append("// === Content from external file (repeated to fill) ===\n");
        while (sb.length() < targetChars) {
            int remaining = targetChars - sb.length();
            if (remaining >= base.length()) {
                sb.append(base);
            } else {
                sb.append(base, 0, remaining);
            }
            if (sb.length() < targetChars) sb.append('\n');
        }
        return sb.toString();
    }

    // ── Random name generators ────────────────────────────────────────

    private static String randomPackage() {
        return "com.example." + randomWord();
    }

    private static String randomClass() {
        var names = new String[]{"DataProcessingService", "CacheManager", "Repository",
                "LockService", "MetricsCollector", "QueryFilter", "RequestValidator",
                "BatchProcessor", "ResultTransformer", "ConfigLoader"};
        return names[RNG.nextInt(names.length)];
    }

    private static String randomTestClass() {
        var names = new String[]{"DataProcessingServiceTest", "CacheManagerTest",
                "RepositoryIntegrationTest", "LockServiceTest", "BatchProcessorTest"};
        return names[RNG.nextInt(names.length)];
    }

    private static String randomHost() {
        return "db-" + randomWord() + ".internal:" + (3306 + RNG.nextInt(10));
    }

    private static String randomId() {
        byte[] b = new byte[4];
        RNG.nextBytes(b);
        return "req-" + HexFormat.of().formatHex(b);
    }

    private static String randomSql() {
        return "SELECT * FROM tb_" + randomWord() + " WHERE id = ? AND status = 'ACTIVE'";
    }

    private static String randomErrorMsg() {
        var msgs = new String[]{"Communications link failure", "Connection refused",
                "Too many connections", "Lock wait timeout exceeded",
                "Deadlock found when trying to get lock"};
        return msgs[RNG.nextInt(msgs.length)];
    }

    private static String randomGrepPattern() {
        var patterns = new String[]{"synchronized|Lock|volatile", "SELECT.*FROM.*WHERE",
                "@Transactional", "Thread\\.sleep", "System\\.out\\.print"};
        return patterns[RNG.nextInt(patterns.length)];
    }

    private static String randomWord() {
        var words = new String[]{"analytics", "pipeline", "gateway", "scheduler",
                "registry", "broker", "dispatcher", "aggregator", "validator", "monitor"};
        return words[RNG.nextInt(words.length)];
    }

    static String filePath(int i) {
        var paths = new String[]{
            "src/main/java/com/example/service/DataProcessingService.java",
            "src/main/java/com/example/service/OrderService.java",
            "src/main/java/com/example/cache/CacheManager.java",
            "src/main/java/com/example/repository/UserRepository.java",
            "src/main/java/com/example/controller/ApiController.java",
            "src/main/java/com/example/config/SecurityConfig.java",
            "src/main/java/com/example/mq/KafkaProducer.java",
            "src/main/java/com/example/mq/KafkaConsumer.java",
            "src/main/java/com/example/lock/DistributedLock.java",
            "src/main/java/com/example/util/DateUtils.java",
            "src/main/java/com/example/model/OrderEntity.java",
            "src/main/java/com/example/model/UserEntity.java",
            "src/main/java/com/example/dto/RequestDTO.java",
            "src/main/java/com/example/dto/ResponseDTO.java",
            "src/main/java/com/example/handler/ExceptionHandler.java",
            "src/main/resources/mapper/UserMapper.xml",
            "src/main/resources/application.yml",
            "src/test/java/com/example/service/ServiceTest.java",
            "src/test/java/com/example/integration/IntegrationTest.java",
            "pom.xml",
        };
        return paths[Math.abs(i) % paths.length];
    }

    private static String methodName(int i) {
        var names = new String[]{"processBatch", "validateRequest", "transformData",
                "queryDatabase", "updateCache", "sendNotification", "handleError",
                "checkPermission", "computeHash", "serializeRecord"};
        return names[Math.abs(i) % names.length];
    }

    private static String cacheKey() {
        return "ck:" + randomWord() + ":" + randomId();
    }

    private static String emptyFor(String returnType) {
        return returnType.contains("List") ? "Collections.emptyList()"
                : returnType.contains("Optional") ? "Optional.empty()"
                : "null";
    }

    private static String randomCodeLine() {
        var lines = new String[]{
            "    private final Map<String, CacheEntry> localCache = new ConcurrentHashMap<>();",
            "    @Override public void afterPropertiesSet() { initScheduler(); }",
            "    if (user == null || !user.isActive()) throw new AccessDeniedException();",
            "    String key = String.format(\"user:%d:session:%s\", userId, sessionId);",
            "    return CompletableFuture.supplyAsync(() -> repository.findById(id));",
            "    try (var conn = dataSource.getConnection()) { /* ... */ }",
            "    @ServiceLock(key = \"#orderId\", type = LockType.REENTRANT)",
            "    var result = restTemplate.postForObject(url, request, ResponseDTO.class);",
            "    redisTemplate.opsForValue().set(key, value, Duration.ofMinutes(30));",
            "    log.info(\"Processing order: {} with {} items\", orderId, items.size());",
            "    Thread.currentThread().interrupt(); // restore interrupt flag",
            "    @KafkaListener(topics = \"order-events\", groupId = \"order-processor\")",
            "    String token = UUID.randomUUID().toString().replace(\"-\", \"\");",
            "    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);",
            "    return IntStream.range(0, batch.size()).parallel().mapToObj(i -> {",
        };
        return lines[RNG.nextInt(lines.length)];
    }
}
