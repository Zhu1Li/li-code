package com.licode.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal line-level diff (LCS) for rendering Write/Edit previews. Not a full
 * Myers implementation — adequate for showing a single edit hunk or a whole-file
 * overwrite. Falls back to "all removed then all added" for very large inputs.
 */
public final class LineDiff {

    private LineDiff() {}

    public enum Type { KEEP, ADD, DEL }

    public record Line(Type type, String text) {}

    private static final int MAX_LINES = 4000;

    public static List<Line> diff(String oldText, String newText) {
        String[] a = oldText.isEmpty() ? new String[0] : oldText.split("\n", -1);
        String[] b = newText.isEmpty() ? new String[0] : newText.split("\n", -1);

        var out = new ArrayList<Line>();
        if (a.length > MAX_LINES || b.length > MAX_LINES) {
            for (String l : a) out.add(new Line(Type.DEL, l));
            for (String l : b) out.add(new Line(Type.ADD, l));
            return out;
        }

        int n = a.length, m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a[i].equals(b[j]) ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }

        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                out.add(new Line(Type.KEEP, a[i])); i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add(new Line(Type.DEL, a[i])); i++;
            } else {
                out.add(new Line(Type.ADD, b[j])); j++;
            }
        }
        while (i < n) out.add(new Line(Type.DEL, a[i++]));
        while (j < m) out.add(new Line(Type.ADD, b[j++]));
        return out;
    }
}
