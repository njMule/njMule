// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Renders the 1280x640 repository social preview from the generated 512 px icon.
 *
 * <p>Usage: {@code java -Djava.awt.headless=true tools/SocialPreview.java
 * branding/generated/njmule-512.png branding/generated/njmule-social-preview.png [font]}
 */
public final class SocialPreview {

    private SocialPreview() {}

    public static void main(String[] args) throws IOException {
        String font = args.length > 2 ? args[2] : "Segoe UI Semibold";
        int width = 1280;
        int height = 640;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(
                RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        g.setColor(new Color(0xF4F6FB));
        g.fillRect(0, 0, width, height);
        Image icon = ImageIO.read(new File(args[0]));
        int size = 360;
        int x = 120;
        g.drawImage(icon, x, (height - size) / 2, size, size, null);

        int tx = x + size + 80;
        g.setColor(new Color(0x24427A));
        g.setFont(new Font(font, Font.BOLD, 132));
        g.drawString("njMule", tx, 290);
        g.setColor(new Color(0xF5A65B));
        g.fill(new RoundRectangle2D.Float(tx + 6, 344, 120, 12, 12, 12));
        g.setColor(new Color(0x4C566A));
        g.setFont(new Font(font, Font.PLAIN, 44));
        g.drawString("Next generation Java Mule", tx + 4, 420);
        g.setColor(new Color(0x6B7385));
        g.setFont(new Font(font, Font.PLAIN, 30));
        g.drawString("eD2K and Kad client  ·  Java 25  ·  GPL-3.0", tx + 4, 472);
        g.dispose();
        ImageIO.write(image, "png", new File(args[1]));
    }
}
