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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Renders the master icon SVG with JSVG into PNG, ICO and ICNS. The ICO and ICNS containers are
 * written here: they only wrap the PNG renders, and no well-known library writes ICNS.
 *
 * <p>Usage: {@code java -cp build/tools/jsvg-<version>.jar tools/IconGen.java
 * branding/njmule-icon.svg <output dir>} (see {@code branding/README.md} for fetching the jar).
 */
public final class IconGen {

    private static final int[] PNG_SIZES = {16, 22, 24, 32, 48, 64, 72, 128, 256, 512, 1024};
    private static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 128, 256};
    // ICNS types holding PNG data, by pixel size.
    private static final Map<String, Integer> ICNS_TYPES = new LinkedHashMap<>();

    static {
        ICNS_TYPES.put("icp4", 16);
        ICNS_TYPES.put("icp5", 32);
        ICNS_TYPES.put("icp6", 64);
        ICNS_TYPES.put("ic07", 128);
        ICNS_TYPES.put("ic08", 256);
        ICNS_TYPES.put("ic09", 512);
        ICNS_TYPES.put("ic10", 1024);
    }

    private IconGen() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java -cp <jsvg.jar> tools/IconGen.java <icon.svg> <output dir>");
            System.exit(2);
        }
        SVGDocument svg = new SVGLoader().load(Path.of(args[0]).toUri().toURL());
        if (svg == null) {
            throw new IOException("cannot read " + args[0]);
        }
        Path out = Files.createDirectories(Path.of(args[1]));

        Map<Integer, byte[]> pngs = new LinkedHashMap<>();
        for (int size : PNG_SIZES) {
            byte[] png = toPng(render(svg, size));
            pngs.put(size, png);
            Files.write(out.resolve("njmule-" + size + ".png"), png);
        }
        Files.write(out.resolve("njmule.ico"), ico(pngs));
        Files.write(out.resolve("njmule.icns"), icns(pngs));
        System.out.println("Icons written to " + out.toAbsolutePath());
    }

    private static BufferedImage render(SVGDocument svg, int size) {
        // Small sizes are drawn at 4x and scaled down for smoother anti-aliasing.
        int scale = size < 64 ? 4 : 1;
        int canvas = size * scale;
        BufferedImage img = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        svg.render((Component) null, g, new ViewBox(0, 0, canvas, canvas));
        g.dispose();
        if (scale == 1) {
            return img;
        }
        BufferedImage small = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = small.createGraphics();
        sg.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        sg.drawImage(img.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        sg.dispose();
        return small;
    }

    private static byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ICO with PNG-compressed entries (supported since Windows Vista).
    private static byte[] ico(Map<Integer, byte[]> pngs) {
        int headerSize = 6 + 16 * ICO_SIZES.length;
        int total = headerSize;
        for (int size : ICO_SIZES) {
            total += pngs.get(size).length;
        }
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) ICO_SIZES.length);
        int offset = headerSize;
        for (int size : ICO_SIZES) {
            byte[] png = pngs.get(size);
            b.put((byte) (size >= 256 ? 0 : size)).put((byte) (size >= 256 ? 0 : size));
            b.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            b.putInt(png.length).putInt(offset);
            offset += png.length;
        }
        for (int size : ICO_SIZES) {
            b.put(pngs.get(size));
        }
        return b.array();
    }

    // ICNS container with PNG entries (big endian).
    private static byte[] icns(Map<Integer, byte[]> pngs) {
        int total = 8;
        for (int size : ICNS_TYPES.values()) {
            total += 8 + pngs.get(size).length;
        }
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        b.put("icns".getBytes(StandardCharsets.US_ASCII)).putInt(total);
        for (Map.Entry<String, Integer> e : ICNS_TYPES.entrySet()) {
            byte[] png = pngs.get(e.getValue());
            b.put(e.getKey().getBytes(StandardCharsets.US_ASCII)).putInt(8 + png.length);
            b.put(png);
        }
        return b.array();
    }
}
