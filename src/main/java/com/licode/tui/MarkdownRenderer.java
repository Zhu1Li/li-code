package com.licode.tui;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Simple terminal markdown renderer. Handles code blocks, inline code,
 * bold, headers, lists, blockquotes, and links.
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    // ANSI escapes for inline regex replacement (match Styles color scheme)
    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String ITALIC = "\033[3m";
    private static final String UNDERLINE = "\033[4m";
    private static final String CYAN = "\033[38;5;80m";
    private static final String GRAY = "\033[38;5;242m";

    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<![*])\\*(.+?)\\*(?![*])");
    private static final Pattern CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\[[;\\d]*m");

    private static String stripAnsi(String s) {
        return ANSI_ESCAPE.matcher(s).replaceAll("");
    }

    public static void render(StringBuilder sb, String markdown, int width) {
        if (markdown == null || markdown.isEmpty()) return;
        int wrapAt = Math.max(width - 2, 20);

        String[] lines = markdown.split("\n", -1);
        boolean inCodeBlock = false;
        String codeBlockLang = "";

        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];

            // Table detection: header row followed by separator row
            if (!inCodeBlock && isTableRow(line) && li + 1 < lines.length
                    && isTableSeparator(lines[li + 1])) {
                var tableRows = new ArrayList<String>();
                tableRows.add(line);
                tableRows.add(lines[li + 1]); // separator
                li += 2;
                while (li < lines.length && isTableRow(lines[li])) {
                    tableRows.add(lines[li]);
                    li++;
                }
                li--; // outer loop will increment
                renderTable(sb, tableRows, width);
                continue;
            }

            if (line.startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    codeBlockLang = line.length() > 3 ? line.substring(3).trim() : "";
                    sb.append(Styles.thinkingContent.render(
                            "┌─ " + (!codeBlockLang.isEmpty() ? codeBlockLang + " " : "") +
                            "─".repeat(Math.max(width - 6 - codeBlockLang.length(), 10))));
                } else {
                    inCodeBlock = false;
                    sb.append(Styles.thinkingContent.render(
                            "└" + "─".repeat(Math.max(width - 4, 10))));
                }
                sb.append('\n');
                continue;
            }

            if (inCodeBlock) {
                sb.append(Styles.thinkingContent.render("│ "));
                sb.append(Styles.inlineDim.render(line)).append('\n');
                continue;
            }

            if (line.startsWith("### ")) {
                sb.append(Styles.banner.render(line.substring(4))).append('\n');
            } else if (line.startsWith("## ")) {
                sb.append(Styles.banner.render(line.substring(3))).append('\n');
            } else if (line.startsWith("# ")) {
                sb.append(Styles.banner.render(line.substring(2))).append('\n');
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                sb.append(Styles.inlineGreen.render("• "));
                renderInlineWrapped(sb, line.substring(2), wrapAt - 2);
                sb.append('\n');
            } else if (line.matches("^\\d+\\. .*")) {
                int dot = line.indexOf(". ");
                sb.append(Styles.inlineCyan.render(line.substring(0, dot + 1))).append(' ');
                renderInlineWrapped(sb, line.substring(dot + 2), wrapAt - 3);
                sb.append('\n');
            } else if (line.startsWith("> ")) {
                for (String wl : wrapLines(line.substring(2), wrapAt - 2)) {
                    sb.append(Styles.thinkingLabel.render("│ "));
                    sb.append(Styles.thinkingContent.render(wl)).append('\n');
                }
            } else if (line.startsWith("---") || line.startsWith("***")) {
                sb.append(Styles.separator.render("─".repeat(Math.max(width - 2, 10)))).append('\n');
            } else if (line.isBlank()) {
                sb.append('\n');
            } else {
                renderInlineWrapped(sb, line, wrapAt);
                sb.append('\n');
            }
        }
    }

    private static void renderInlineWrapped(StringBuilder sb, String text, int maxWidth) {
        text = BOLD_PATTERN.matcher(text).replaceAll(BOLD + "$1" + RESET);
        text = ITALIC_PATTERN.matcher(text).replaceAll(ITALIC + "$1" + RESET);
        text = CODE_PATTERN.matcher(text).replaceAll(CYAN + "`$1`" + RESET);
        text = LINK_PATTERN.matcher(text).replaceAll(UNDERLINE + CYAN + "$1" + RESET);

        // Wrap long lines respecting display width
        for (String line : wrapLines(text, maxWidth)) {
            sb.append(line).append('\n');
        }
    }

    private static java.util.List<String> wrapLines(String text, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        if (maxWidth < 1) maxWidth = 1;

        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();

        for (String word : words) {
            int spaceNeeded = line.isEmpty() ? 0 : 1;
            int lineDw = displayWidth(line.toString());
            int wordDw = displayWidth(word);
            if (lineDw + spaceNeeded + wordDw <= maxWidth) {
                if (spaceNeeded > 0) line.append(' ');
                line.append(word);
            } else {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                while (displayWidth(word) > maxWidth) {
                    int splitAt = splitAtDisplayWidth(word, maxWidth);
                    lines.add(word.substring(0, splitAt));
                    word = word.substring(splitAt);
                }
                line.append(word);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static int splitAtDisplayWidth(String s, int maxWidth) {
        String plain = stripAnsi(s);
        int w = 0;
        for (int i = 0; i < plain.length(); i++) {
            int cp = plain.codePointAt(i);
            int cw = (cp >= 0x2E80) ? 2 : 1;
            if (w + cw > maxWidth) return i;
            w += cw;
            if (Character.isSupplementaryCodePoint(cp)) i++;
        }
        return s.length();
    }

    // ── Table rendering (public for GUI reuse) ───────────────────────

    public static boolean isTableRow(String line) {
        return line != null && line.strip().startsWith("|") && line.strip().endsWith("|");
    }

    public static boolean isTableSeparator(String line) {
        return line != null
                && line.strip().matches("^\\|[\\s:\\-|]+\\|$")
                && line.contains("---");
    }

    public static int displayWidth(String s) {
        String plain = stripAnsi(s);
        int w = 0;
        for (int i = 0; i < plain.length(); i++) {
            int cp = plain.codePointAt(i);
            if (Character.isSupplementaryCodePoint(cp)) i++;
            w += (cp >= 0x2E80 && cp <= 0xA4CF
                    || cp >= 0xA960 && cp <= 0xA97C
                    || cp >= 0xAC00 && cp <= 0xD7AF
                    || cp >= 0xF900 && cp <= 0xFAFF
                    || cp >= 0xFE10 && cp <= 0xFE19
                    || cp >= 0xFE30 && cp <= 0xFE6F
                    || cp >= 0xFF01 && cp <= 0xFF60
                    || cp >= 0xFFE0 && cp <= 0xFFE6
                    || cp >= 0x1F300 && cp <= 0x1F64F
                    || cp >= 0x1F900 && cp <= 0x1F9FF
                    || cp >= 0x20000 && cp <= 0x2FFFD
                    || cp >= 0x30000 && cp <= 0x3FFFD) ? 2 : 1;
        }
        return w;
    }

    public static java.util.List<String> parseTableRow(String line) {
        var cells = new java.util.ArrayList<String>();
        String stripped = line.strip();
        if (stripped.startsWith("|")) stripped = stripped.substring(1);
        if (stripped.endsWith("|")) stripped = stripped.substring(0, stripped.length() - 1);
        for (String cell : stripped.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    /** Extract alignment per column from a separator row like |:---|---:|:---:|. */
    public static String[] tableAlignments(String separator) {
        var cells = parseTableRow(separator);
        String[] alignments = new String[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            String cell = cells.get(i).trim();
            boolean left = cell.startsWith(":");
            boolean right = cell.endsWith(":");
            if (left && right) alignments[i] = "center";
            else if (right) alignments[i] = "right";
            else alignments[i] = "left";
        }
        return alignments;
    }

    /**
     * Render a GFM pipe table. tableRows[0] is the header, tableRows[1] is the
     * separator (used for alignment), and the remaining rows are data.
     */
    private static void renderTable(StringBuilder sb, java.util.List<String> tableRows, int width) {
        if (tableRows.size() < 2) return;

        // Parse header and separator
        var headers = parseTableRow(tableRows.get(0));
        var alignments = tableAlignments(tableRows.get(1));
        int cols = headers.size();
        if (cols == 0) return;

        // Parse data rows
        var dataRows = new java.util.ArrayList<java.util.List<String>>();
        for (int r = 2; r < tableRows.size(); r++) {
            var cells = parseTableRow(tableRows.get(r));
            // Pad to match header column count
            while (cells.size() < cols) cells.add("");
            if (cells.size() > cols) cells = cells.subList(0, cols);
            dataRows.add(cells);
        }

        // Calculate column widths
        int[] colWidths = new int[cols];
        for (int c = 0; c < cols; c++) {
            colWidths[c] = displayWidth(headers.get(c).trim());
        }
        for (var row : dataRows) {
            for (int c = 0; c < cols; c++) {
                int dw = displayWidth(row.get(c).trim());
                if (dw > colWidths[c]) colWidths[c] = dw;
            }
        }

        // Clamp to available width: if total exceeds width, shrink widest columns
        int gutter = 3 * cols + 1; // "| " + " | " per col + final "|"
        int total = gutter;
        for (int w : colWidths) total += w;
        if (total > width) {
            int excess = total - width;
            // Shrink columns proportionally, but enforce min width of 3
            for (int pass = 0; pass < 2 && excess > 0; pass++) {
                for (int c = 0; c < cols && excess > 0; c++) {
                    int reduce = Math.min(excess, Math.max(0, colWidths[c] - 3));
                    colWidths[c] -= reduce;
                    excess -= reduce;
                }
            }
        }

        // Top border
        sb.append(Styles.inlineDim.render("┌"));
        for (int c = 0; c < cols; c++) {
            if (c > 0) sb.append(Styles.inlineDim.render("┬"));
            sb.append(Styles.inlineDim.render("─".repeat(colWidths[c] + 2)));
        }
        sb.append(Styles.inlineDim.render("┐")).append('\n');

        // Header row
        renderTableRow(sb, headers, colWidths, alignments, true);

        // Separator
        sb.append(Styles.inlineDim.render("├"));
        for (int c = 0; c < cols; c++) {
            if (c > 0) sb.append(Styles.inlineDim.render("┼"));
            sb.append(Styles.inlineDim.render("─".repeat(colWidths[c] + 2)));
        }
        sb.append(Styles.inlineDim.render("┤")).append('\n');

        // Data rows
        for (var row : dataRows) {
            renderTableRow(sb, row, colWidths, alignments, false);
        }

        // Bottom border
        sb.append(Styles.inlineDim.render("└"));
        for (int c = 0; c < cols; c++) {
            if (c > 0) sb.append(Styles.inlineDim.render("┴"));
            sb.append(Styles.inlineDim.render("─".repeat(colWidths[c] + 2)));
        }
        sb.append(Styles.inlineDim.render("┘")).append('\n');
    }

    private static void renderTableRow(StringBuilder sb, java.util.List<String> cells,
                                       int[] colWidths, String[] alignments, boolean header) {
        sb.append(Styles.inlineDim.render("│"));
        for (int c = 0; c < colWidths.length; c++) {
            if (c > 0) sb.append(Styles.inlineDim.render("│"));
            String cell = c < cells.size() ? cells.get(c).trim() : "";
            String aligned = alignCell(cell, colWidths[c], c < alignments.length ? alignments[c] : "left");
            if (header) {
                sb.append(' ').append(BOLD).append(aligned).append(RESET).append(' ');
            } else {
                sb.append(' ').append(aligned).append(' ');
            }
        }
        sb.append(Styles.inlineDim.render("│")).append('\n');
    }

    private static String alignCell(String cell, int width, String alignment) {
        int dw = displayWidth(cell);
        if (dw >= width) return cell; // no padding needed (or truncated)
        int pad = width - dw;
        return switch (alignment) {
            case "right" -> " ".repeat(pad) + cell;
            case "center" -> " ".repeat(pad / 2) + cell + " ".repeat(pad - pad / 2);
            default -> cell + " ".repeat(pad); // left
        };
    }
}
