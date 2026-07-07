package com.licode.gui;

import com.licode.tui.ChatMessage;
import com.licode.tui.MarkdownRenderer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatBubble extends VBox {

    private static final Pattern INLINE_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern INLINE_ITALIC = Pattern.compile("\\*(.+?)\\*");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern INLINE_MATH = Pattern.compile("\\$([^$]+)\\$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$");

    private final ChatMessage message;
    private final VBox contentBox;
    private TextFlow currentFlow;
    private final StringBuilder rawText = new StringBuilder();
    private TitledPane thinkingPane;
    private Text thinkingText;

    public ChatBubble(ChatMessage message) {
        this.message = message;
        setSpacing(3);
        setMaxWidth(Double.MAX_VALUE);

        String styleClass = switch (message.getRole()) {
            case USER -> "chat-bubble-user";
            case ASSISTANT, THINKING -> "chat-bubble-assistant";
            case SYSTEM -> "chat-bubble-system";
            case ERROR -> "chat-bubble-error";
        };
        getStyleClass().add(styleClass);

        // Role label
        if (message.getRole() != ChatMessage.Role.ASSISTANT || message.getContent().isEmpty()) {
            Label roleLabel = new Label(roleLabelText());
            roleLabel.getStyleClass().addAll("role-label", roleStyleClass());
            getChildren().add(roleLabel);
        }

        // Thinking content (collapsible, light gray text)
        if (message.getThinkingContent() != null && !message.getThinkingContent().isEmpty()) {
            TitledPane thinkingPane = new TitledPane();
            thinkingPane.setText("Thinking");
            thinkingPane.getStyleClass().add("thinking-pane");
            thinkingPane.setExpanded(message.isThinkingExpanded());
            thinkingPane.setMaxWidth(Double.MAX_VALUE);
            this.thinkingPane = thinkingPane;
            var thinkingFlow = new TextFlow();
            thinkingFlow.setMaxWidth(Double.MAX_VALUE);
            this.thinkingText = new Text(message.getThinkingContent());
            thinkingText.getStyleClass().add("thinking-content");
            thinkingFlow.getChildren().add(thinkingText);
            thinkingPane.setContent(thinkingFlow);
            getChildren().add(thinkingPane);
        }

        // Content
        this.contentBox = new VBox(0);
        contentBox.setMaxWidth(Double.MAX_VALUE);
        contentBox.setFillWidth(true);
        if (message.getContent() != null && !message.getContent().isEmpty()) {
            rawText.append(message.getContent());
            renderContent(message.getContent());
        }
        getChildren().add(contentBox);

        // Copy button for text selection workaround (TextFlow doesn't support native selection)
        if (message.getRole() != ChatMessage.Role.SYSTEM && message.getRole() != ChatMessage.Role.ERROR) {
            Button copyBtn = new Button("Copy");
            copyBtn.getStyleClass().add("copy-button");
            copyBtn.setTooltip(new Tooltip("Copy message to clipboard"));
            copyBtn.setOnAction(e -> {
                Clipboard.getSystemClipboard().setContent(
                        new ClipboardContent() {{ putString(rawText.toString()); }});
                copyBtn.setText("Copied!");
                // Reset after 2 seconds
                new Thread(() -> {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    javafx.application.Platform.runLater(() -> copyBtn.setText("Copy"));
                }).start();
            });
            getChildren().add(copyBtn);
        }

        // Alignment: user messages to the right
        if (message.getRole() == ChatMessage.Role.USER) {
            setAlignment(Pos.TOP_RIGHT);
            setMaxWidth(Double.MAX_VALUE);
        }
    }

    public TextFlow getContentFlow() {
        if (currentFlow == null) {
            currentFlow = newTextFlow();
            contentBox.getChildren().add(currentFlow);
        }
        return currentFlow;
    }

    private TextFlow newTextFlow() {
        TextFlow tf = new TextFlow();
        tf.setLineSpacing(3);
        tf.setMaxWidth(Double.MAX_VALUE);
        return tf;
    }

    void appendRawText(String text) {
        rawText.append(text);
    }

    void reRenderContent() {
        String fullText = rawText.toString();
        if (fullText.isEmpty()) return;
        contentBox.getChildren().clear();
        currentFlow = null;
        renderContent(fullText);
    }

    // ── Full-content renderer with code-block support ─────────────────

    private void renderContent(String text) {
        currentFlow = null;

        // Normalize line endings so table/code-block detection works on all platforms
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");

        boolean inCodeBlock = false;
        StringBuilder codeBuf = new StringBuilder();
        boolean inMathBlock = false;
        StringBuilder mathBuf = new StringBuilder();
        String[] lines = normalized.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean isLast = (i == lines.length - 1);

            // Block math: $$ ... $$
            if (line.strip().equals("$$")) {
                if (inMathBlock) {
                    flushMathBlock(mathBuf.toString().trim());
                    mathBuf.setLength(0);
                    inMathBlock = false;
                } else {
                    inMathBlock = true;
                }
                continue;
            }

            if (inMathBlock) {
                if (!mathBuf.isEmpty()) mathBuf.append("\n");
                mathBuf.append(line);
                continue;
            }

            if (line.startsWith("```")) {
                if (inCodeBlock) {
                    flushCodeBlock(codeBuf.toString());
                    codeBuf.setLength(0);
                    inCodeBlock = false;
                } else {
                    inCodeBlock = true;
                }
                continue;
            }

            if (inCodeBlock) {
                if (!codeBuf.isEmpty()) codeBuf.append("\n");
                codeBuf.append(line);
                continue;
            }

            // Table detection: header row + separator row (delegates to MarkdownRenderer)
            if (MarkdownRenderer.isTableRow(line)
                    && i + 1 < lines.length
                    && MarkdownRenderer.isTableSeparator(lines[i + 1])) {
                List<String> rawRows = new ArrayList<>();
                rawRows.add(line);
                rawRows.add(lines[i + 1]); // separator
                i += 2;
                while (i < lines.length && MarkdownRenderer.isTableRow(lines[i])) {
                    rawRows.add(lines[i]);
                    i++;
                }
                i--; // outer loop will increment
                flushTable(rawRows);
                if (!isLast) ensureFlow().getChildren().add(new Text("\n"));
                continue;
            }

            // Regular line
            List<Node> lineNodes = renderLine(line);
            if (!lineNodes.isEmpty()) {
                ensureFlow().getChildren().addAll(lineNodes);
            }
            if (!isLast) {
                ensureFlow().getChildren().add(new Text("\n"));
            }
        }

        // Unclosed blocks — flush anyway
        if (inMathBlock && !mathBuf.isEmpty()) {
            flushMathBlock(mathBuf.toString().trim());
        }
        if (inCodeBlock && !codeBuf.isEmpty()) {
            flushCodeBlock(codeBuf.toString());
        }
    }

    private TextFlow ensureFlow() {
        if (currentFlow == null) {
            currentFlow = newTextFlow();
            contentBox.getChildren().add(currentFlow);
        }
        return currentFlow;
    }

    private void flushCodeBlock(String code) {
        currentFlow = null;

        TextFlow codeBlock = new TextFlow();
        codeBlock.getStyleClass().add("code-block-box");
        codeBlock.setMaxWidth(Double.MAX_VALUE);
        Text codeText = new Text(code);
        codeText.getStyleClass().add("text-code-block");
        codeBlock.getChildren().add(codeText);
        contentBox.getChildren().add(codeBlock);
    }

    private void flushMathBlock(String latex) {
        currentFlow = null;

        Image image = LatexRenderer.renderBlock(latex.strip());
        if (image != null) {
            ImageView iv = new ImageView(image);
            iv.setPreserveRatio(true);
            iv.getStyleClass().add("math-block");
            HBox wrapper = new HBox(iv);
            wrapper.setAlignment(Pos.CENTER);
            wrapper.setPadding(new Insets(8, 0, 8, 0));
            wrapper.setMaxWidth(Double.MAX_VALUE);
            contentBox.getChildren().add(wrapper);
        } else {
            // Fallback: render as code if LaTeX parsing fails
            TextFlow fallback = new TextFlow();
            fallback.getStyleClass().add("code-block-box");
            Text t = new Text(latex);
            t.getStyleClass().add("text-code-block");
            fallback.getChildren().add(t);
            contentBox.getChildren().add(fallback);
        }
    }

    // ── Table rendering (delegates parsing to MarkdownRenderer) ──────

    private void flushTable(List<String> rawRows) {
        currentFlow = null;
        if (rawRows.size() < 2) return;

        var headers = MarkdownRenderer.parseTableRow(rawRows.get(0));
        var alignments = MarkdownRenderer.tableAlignments(rawRows.get(1));
        int cols = headers.size();
        if (cols == 0) return;

        // Parse data rows
        var dataRows = new ArrayList<java.util.List<String>>();
        for (int r = 2; r < rawRows.size(); r++) {
            var cells = MarkdownRenderer.parseTableRow(rawRows.get(r));
            while (cells.size() < cols) cells.add("");
            if (cells.size() > cols) cells = cells.subList(0, cols);
            dataRows.add(cells);
        }

        // Strip inline markdown from all cells
        for (int c = 0; c < cols; c++) headers.set(c, stripInline(headers.get(c)));
        for (var row : dataRows) {
            for (int c = 0; c < cols; c++) row.set(c, stripInline(row.get(c)));
        }

        // GridPane ensures column widths are consistent across all rows,
        // unlike independent HBox rows whose per-row widths drift apart.
        GridPane grid = new GridPane();
        grid.getStyleClass().add("table-block-vbox");
        grid.setMaxWidth(Double.MAX_VALUE);
        grid.setHgap(0);
        grid.setVgap(0);

        // Equal-width columns
        for (int c = 0; c < cols; c++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / cols);
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
        }

        // Header row
        for (int c = 0; c < cols; c++) {
            Label cell = new Label(headers.get(c));
            styleCell(cell, alignments, c, true);
            GridPane.setFillWidth(cell, true);
            grid.add(cell, c, 0);
        }

        // Data rows
        for (int r = 0; r < dataRows.size(); r++) {
            var row = dataRows.get(r);
            for (int c = 0; c < cols; c++) {
                Label cell = new Label(row.get(c));
                styleCell(cell, alignments, c, false);
                GridPane.setFillWidth(cell, true);
                grid.add(cell, c, r + 1);
            }
        }

        contentBox.getChildren().add(grid);
    }

    private static void styleCell(Label cell, String[] alignments, int col, boolean header) {
        cell.getStyleClass().add("table-cell");
        if (header) cell.getStyleClass().add("table-header-cell");
        cell.setMaxWidth(Double.MAX_VALUE);
        cell.setWrapText(true);
        String align = col < alignments.length ? alignments[col] : "left";
        switch (align) {
            case "right" -> cell.setAlignment(Pos.CENTER_RIGHT);
            case "center" -> cell.setAlignment(Pos.CENTER);
            default -> cell.setAlignment(Pos.CENTER_LEFT);
        }
    }

    private static String stripInline(String s) {
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("(?<![*])\\*(.+?)\\*(?![*])", "$1")
                .replaceAll("`([^`]+)`", "$1");
    }

    private List<Node> renderLine(String line) {
        List<Node> nodes = new ArrayList<>();

        // Heading
        Matcher hm = HEADING.matcher(line);
        if (hm.matches()) {
            Text t = new Text(hm.group(2));
            String level = hm.group(1);
            t.getStyleClass().add(level.length() == 1 ? "text-h1" : level.length() == 2 ? "text-h2" : "text-h3");
            nodes.add(t);
            return nodes;
        }

        // Bullet list: "- item" or "* item"
        if (line.matches("^\\s*[-*]\\s+.+")) {
            String content = line.replaceFirst("^\\s*[-*]\\s+", "");
            Text bullet = new Text("  • ");
            bullet.getStyleClass().add("text-primary");
            nodes.add(bullet);
            nodes.addAll(inlineNodes(content));
            return nodes;
        }

        // Numbered list: "1. item"
        if (line.matches("^\\s*\\d+\\.\\s+.+")) {
            String prefix = line.replaceFirst("^(\\s*\\d+\\.\\s+).+", "$1");
            String content = line.replaceFirst("^\\s*\\d+\\.\\s+", "");
            Text num = new Text("  " + prefix);
            num.getStyleClass().add("text-primary");
            nodes.add(num);
            nodes.addAll(inlineNodes(content));
            return nodes;
        }

        // Blockquote: "> quote"
        if (line.startsWith(">")) {
            String content = line.replaceFirst("^>\\s?", "");
            Text t = new Text(content);
            t.getStyleClass().add("text-blockquote");
            nodes.add(t);
            return nodes;
        }

        // Horizontal rule
        if (line.matches("^[-*_]{3,}$")) {
            Text t = new Text("─".repeat(40));
            t.getStyleClass().add("text-muted");
            nodes.add(t);
            return nodes;
        }

        // Regular line with inline formatting
        if (!line.isEmpty()) {
            nodes.addAll(inlineNodes(line));
        }
        return nodes;
    }

    // ── Inline formatting (bold / italic / inline code) ──────────────

    static List<Node> inlineNodes(String text) {
        List<Node> nodes = new ArrayList<>();
        int pos = 0;

        while (pos < text.length()) {
            Matcher boldMatcher = INLINE_BOLD.matcher(text);
            Matcher italicMatcher = INLINE_ITALIC.matcher(text);
            Matcher codeMatcher = INLINE_CODE.matcher(text);
            Matcher mathMatcher = INLINE_MATH.matcher(text);

            int boldStart = boldMatcher.find(pos) ? boldMatcher.start() : -1;
            int italicStart = italicMatcher.find(pos) ? italicMatcher.start() : -1;
            int codeStart = codeMatcher.find(pos) ? codeMatcher.start() : -1;
            int mathStart = mathMatcher.find(pos) ? mathMatcher.start() : -1;

            int nextMatch = minPositive(boldStart, italicStart, codeStart, mathStart);

            if (nextMatch < 0) {
                if (pos < text.length()) {
                    nodes.add(primaryText(text.substring(pos)));
                }
                break;
            }

            if (nextMatch > pos) {
                nodes.add(primaryText(text.substring(pos, nextMatch)));
            }

            if (nextMatch == boldStart) {
                boldMatcher.find(nextMatch);
                for (Node n : inlineNodes(boldMatcher.group(1))) {
                    if (n instanceof Text t) t.getStyleClass().add("text-bold");
                    nodes.add(n);
                }
                pos = boldMatcher.end();
            } else if (nextMatch == italicStart) {
                italicMatcher.find(nextMatch);
                for (Node n : inlineNodes(italicMatcher.group(1))) {
                    if (n instanceof Text t) t.getStyleClass().add("text-italic");
                    nodes.add(n);
                }
                pos = italicMatcher.end();
            } else if (nextMatch == codeStart) {
                codeMatcher.find(nextMatch);
                Text t = primaryText(codeMatcher.group(1));
                t.getStyleClass().add("text-inline-code");
                nodes.add(t);
                pos = codeMatcher.end();
            } else {
                mathMatcher.find(nextMatch);
                String latex = mathMatcher.group(1);
                Image image = LatexRenderer.renderInline(latex);
                if (image != null) {
                    ImageView iv = new ImageView(image);
                    iv.setPreserveRatio(true);
                    iv.setFitHeight(18);
                    iv.getStyleClass().add("math-inline");
                    nodes.add(iv);
                } else {
                    Text t = primaryText(latex);
                    t.getStyleClass().add("text-italic");
                    nodes.add(t);
                }
                pos = mathMatcher.end();
            }
        }
        return nodes;
    }

    /**
     * Legacy entry point used by ChatView streaming — each text delta is fed
     * here. During streaming the full-content renderer in renderContent() is
     * not used; instead nodes are appended incrementally.
     */
    static List<Node> parseStyledText(String text) {
        // During streaming, short deltas are virtually always plain text.
        // Only bother with inline parsing when deltas contain markup.
        if (text.contains("**") || text.contains("*") || text.contains("`") || text.contains("$")) {
            return inlineNodes(text);
        }
        return List.of(primaryText(text));
    }

    private static int minPositive(int... values) {
        int min = -1;
        for (int v : values) {
            if (v >= 0 && (min < 0 || v < min)) min = v;
        }
        return min;
    }

    private static Text primaryText(String text) {
        Text t = new Text(text);
        t.getStyleClass().add("text-primary");
        return t;
    }

    // ── Role helpers ─────────────────────────────────────────────────

    private String roleLabelText() {
        return switch (message.getRole()) {
            case USER -> "You";
            case ASSISTANT -> "LiCode";
            case SYSTEM -> "System";
            case ERROR -> "Error";
            case THINKING -> "Thinking";
        };
    }

    private String roleStyleClass() {
        return switch (message.getRole()) {
            case USER -> "role-user";
            case ASSISTANT -> "role-assistant";
            case SYSTEM -> "role-system";
            case ERROR -> "role-error";
            case THINKING -> "role-system";
        };
    }

    /** Swap base style to tool-specific appearance. */
    void markAsTool() {
        getStyleClass().remove("chat-bubble-system");
        getStyleClass().add("chat-bubble-tool");
    }

    void updateThinking(String text) {
        if (thinkingText != null) {
            thinkingText.setText(text);
        }
    }

    void setThinkingExpanded(boolean expanded) {
        if (thinkingPane != null) {
            thinkingPane.setExpanded(expanded);
        }
    }
}
