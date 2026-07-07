package com.licode.failure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 项目级"失败记忆"库：把每次"bug → 根因 → 修复"沉淀成一条 lesson，下次遇到相似 bug
 * 先 recall 历史、少走弯路。
 *
 * <p>刻意**独立于** {@code MemoryManager}：失败库会持续增长，绝不能走 MemoryManager 的
 * 全量注入（那会撑爆上下文——"短且恒相关的才该全量注入"这条红线）。这里只走**选择性检索**：
 * {@link #recall} 按关键词模糊匹配取 top-k。
 *
 * <p>落盘：{@code <workDir>/.licode/failures/<slug>.md}，每条一个文件；{@code INDEX.md} 供人查阅。
 * 匹配读各 lesson 文件的关键词（N 很小，直接全读）。所有 I/O 失败都兜底、不抛异常崩调用方。
 */
public final class FailureStore {

    static final String FAILURES_DIR = ".licode/failures";
    public static final int DEFAULT_RECALL_LIMIT = 3;
    static final int MAX_RECALL_LIMIT = 5;

    private static final Pattern FRONTMATTER =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);

    private final Path dir;

    public FailureStore(Path workDir) {
        this.dir = workDir.resolve(FAILURES_DIR);
    }

    /** 一条失败教训。keywords 用于相似度匹配；rootCause/fix 是给下次参考的正文。 */
    public record FailureLesson(List<String> keywords, String rootCause, String fix,
                                String testName, String exception, Instant timestamp) {}

    Path dir() {
        return dir;
    }

    /** 写入一条 lesson（不同 bug 用不同 slug，不互相覆盖）。 */
    public void record(FailureLesson lesson) {
        if (lesson == null) return;
        try {
            Files.createDirectories(dir);
            String base = slugFromKeywords(lesson.keywords());
            // 时间戳后缀去重：不同时间记录的相似 bug 各留一条。
            String slug = base + "-" + Long.toString(
                    (lesson.timestamp() != null ? lesson.timestamp() : Instant.now()).toEpochMilli(), 36);
            String fileName = slug + ".md";

            String kw = lesson.keywords() == null ? "" : String.join(", ", lesson.keywords());
            String body = "---\n"
                    + "keywords: " + kw + "\n"
                    + "test: " + nz(lesson.testName()) + "\n"
                    + "exception: " + nz(lesson.exception()) + "\n"
                    + "timestamp: " + (lesson.timestamp() != null ? lesson.timestamp() : Instant.now()) + "\n"
                    + "---\n\n"
                    + "## Root cause\n" + nz(lesson.rootCause()) + "\n\n"
                    + "## Fix\n" + nz(lesson.fix()) + "\n";
            Files.writeString(dir.resolve(fileName), body);
            appendIndex(fileName, kw);
        } catch (IOException e) {
            System.err.println("[LiCode] FailureStore write error: " + e.getMessage());
        }
    }

    /**
     * 关键词模糊匹配：把 query 分词，与每条 lesson 的关键词求重叠数打分，取前 k。
     * 无命中返回空列表。k 会被夹到 [1, MAX_RECALL_LIMIT]。
     */
    public List<FailureLesson> recall(String query, int k) {
        int limit = Math.max(1, Math.min(k <= 0 ? DEFAULT_RECALL_LIMIT : k, MAX_RECALL_LIMIT));
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) return List.of();

        var scored = new ArrayList<Scored>();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("INDEX.md"))
                    .forEach(p -> {
                        FailureLesson lesson = parse(p);
                        if (lesson == null) return;
                        Set<String> lessonTokens = new HashSet<>();
                        for (String kw : lesson.keywords()) lessonTokens.addAll(tokenize(kw));
                        long overlap = queryTokens.stream().filter(lessonTokens::contains).count();
                        if (overlap > 0) scored.add(new Scored(lesson, overlap));
                    });
        } catch (IOException e) {
            return List.of();
        }

        scored.sort((a, b) -> Long.compare(b.score, a.score));
        return scored.stream().limit(limit).map(s -> s.lesson).collect(Collectors.toList());
    }

    // ── internals ────────────────────────────────────────────────────

    private record Scored(FailureLesson lesson, long score) {}

    private void appendIndex(String fileName, String keywords) {
        try {
            Path index = dir.resolve("INDEX.md");
            String line = "- " + fileName + " — " + keywords + "\n";
            if (Files.exists(index)) {
                Files.writeString(index, line, StandardOpenOption.APPEND);
            } else {
                Files.writeString(index, "# Failure lessons\n\n" + line);
            }
        } catch (IOException ignored) {
            // best-effort index
        }
    }

    private FailureLesson parse(Path file) {
        try {
            String raw = Files.readString(file);
            Matcher m = FRONTMATTER.matcher(raw);
            if (!m.find()) return null;
            String front = m.group(1);
            String bodyText = m.group(2);

            List<String> keywords = splitCsv(field(front, "keywords"));
            String test = field(front, "test");
            String exception = field(front, "exception");
            Instant ts = parseInstant(field(front, "timestamp"));
            String rootCause = section(bodyText, "Root cause");
            String fix = section(bodyText, "Fix");
            return new FailureLesson(keywords, rootCause, fix, test, exception, ts);
        } catch (IOException e) {
            return null;
        }
    }

    private static String field(String frontmatter, String key) {
        for (String line : frontmatter.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.startsWith(key + ":")) {
                return trimmed.substring((key + ":").length()).strip();
            }
        }
        return "";
    }

    private static String section(String body, String header) {
        var sb = new StringBuilder();
        boolean in = false;
        for (String line : body.lines().toList()) {
            if (line.startsWith("## ")) {
                in = line.substring(3).strip().equalsIgnoreCase(header);
                continue;
            }
            if (in) sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    private static Instant parseInstant(String s) {
        try {
            return (s == null || s.isBlank()) ? Instant.now() : Instant.parse(s);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    /** 分词：小写 + 按非字母数字切，去掉 ≤1 字符的碎片。 */
    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> t.length() > 1)
                .collect(Collectors.toSet());
    }

    private static String slugFromKeywords(List<String> keywords) {
        String joined = (keywords == null || keywords.isEmpty()) ? "failure" : String.join("-", keywords);
        String slug = joined.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("-+", "-");
        slug = slug.replaceAll("^-|-$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40);
        return slug.isEmpty() ? "failure" : slug;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
