package com.licode.gui;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public final class LatexRenderer {

    private static final Color FG = Color.web("#cdd6f4");
    private static final float SCALE = 1.5f;

    private LatexRenderer() {}

    /** Render a block-level LaTeX formula ($$...$$). */
    public static Image renderBlock(String latex) {
        try {
            TeXFormula formula = new TeXFormula(latex);
            TeXIcon icon = formula.createTeXIcon(TeXConstants.STYLE_DISPLAY, 20 * SCALE);

            int w = icon.getIconWidth() + 8;
            int h = icon.getIconHeight() + 8;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(toAwt(FG));
            icon.paintIcon(null, g2, 4, 4);
            g2.dispose();
            return SwingFXUtils.toFXImage(img, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Render an inline LaTeX formula ($...$). */
    public static Image renderInline(String latex) {
        try {
            TeXFormula formula = new TeXFormula(latex);
            TeXIcon icon = formula.createTeXIcon(TeXConstants.STYLE_TEXT, 16 * SCALE);

            int w = icon.getIconWidth() + 4;
            int h = icon.getIconHeight() + 2;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(toAwt(FG));
            icon.paintIcon(null, g2, 2, 1);
            g2.dispose();
            return SwingFXUtils.toFXImage(img, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static java.awt.Color toAwt(Color c) {
        return new java.awt.Color((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue());
    }
}
