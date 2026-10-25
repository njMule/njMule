/*
 * njMule - Next generation Java Mule
 * SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <https://www.gnu.org/licenses/>.
 */
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Renders the 4:3 country flags of flag-icons (MIT) with JSVG into small PNGs, one per ISO 3166-1 alpha-2 code: the
 * desktop app shows them beside clients, and the web interface serves the same files.
 *
 * <p>Usage: {@code java -cp build/tools/jsvg-<version>.jar tools/FlagGen.java <flag-icons>/flags/4x3
 * njmule-gui/src/main/resources/org/njmule/gui/flags}.
 */
public final class FlagGen {

    // Twice the size shown, for screens that scale by two.
    private static final int WIDTH = 32;
    private static final int HEIGHT = 24;
    private static final int OVERSAMPLE = 4;

    private FlagGen() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java -cp <jsvg.jar> tools/FlagGen.java <flags/4x3 dir> <output dir>");
            System.exit(2);
        }
        Path out = Files.createDirectories(Path.of(args[1]));
        int written = 0;
        try (DirectoryStream<Path> flags = Files.newDirectoryStream(Path.of(args[0]), "[a-z][a-z].svg")) {
            for (Path flag : flags) {
                SVGDocument svg = new SVGLoader().load(flag.toUri().toURL());
                if (svg == null) {
                    throw new IOException("cannot read " + flag);
                }
                String code = flag.getFileName().toString().substring(0, 2);
                ImageIO.write(render(svg), "png", out.resolve(code + ".png").toFile());
                written++;
            }
        }
        System.out.println(written + " flags written to " + out.toAbsolutePath());
    }

    // Drawn larger and scaled down, for smoother edges.
    private static BufferedImage render(SVGDocument svg) {
        BufferedImage big =
                new BufferedImage(WIDTH * OVERSAMPLE, HEIGHT * OVERSAMPLE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        svg.render((Component) null, g, new ViewBox(0, 0, big.getWidth(), big.getHeight()));
        g.dispose();
        BufferedImage small = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = small.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        sg.drawImage(big.getScaledInstance(WIDTH, HEIGHT, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        sg.dispose();
        return small;
    }
}
