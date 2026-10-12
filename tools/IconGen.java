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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Renders the master icon SVG into PNG, ICO and ICNS without third-party libraries.
 *
 * <p>Only the SVG subset used by {@code branding/} is supported: {@code rect} (with {@code rx}),
 * {@code circle}, {@code path} with M/L/C/Q/Z absolute commands, solid fills and round strokes.
 *
 * <p>Usage: {@code java tools/IconGen.java branding/njmule-icon.svg <output dir>}
 */
public final class IconGen {

    private static final int[] PNG_SIZES = {16, 22, 24, 32, 48, 64, 128, 256, 512, 1024};
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

    private static final Pattern ELEMENT = Pattern.compile("<(rect|circle|path)\\b([^>]*)/>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private static final Pattern VIEWBOX = Pattern.compile("viewBox=\"([^\"]+)\"");

    private record Shape(java.awt.Shape geometry, Color fill, Color stroke, float strokeWidth) {}

    private IconGen() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java tools/IconGen.java <icon.svg> <output dir>");
            System.exit(2);
        }
        String svg = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        double viewSize = viewBoxSize(svg);
        List<Shape> shapes = parse(svg);
        Path out = Files.createDirectories(Path.of(args[1]));

        Map<Integer, byte[]> pngs = new LinkedHashMap<>();
        for (int size : PNG_SIZES) {
            byte[] png = toPng(render(shapes, viewSize, size));
            pngs.put(size, png);
            Files.write(out.resolve("njmule-" + size + ".png"), png);
        }
        Files.write(out.resolve("njmule.ico"), ico(pngs));
        Files.write(out.resolve("njmule.icns"), icns(pngs, shapes, viewSize));
        System.out.println("Icons written to " + out.toAbsolutePath());
    }

    private static double viewBoxSize(String svg) {
        Matcher m = VIEWBOX.matcher(svg);
        if (!m.find()) {
            throw new IllegalArgumentException("viewBox is required");
        }
        String[] parts = m.group(1).trim().split("[\\s,]+");
        return Double.parseDouble(parts[2]);
    }

    private static List<Shape> parse(String svg) {
        List<Shape> shapes = new ArrayList<>();
        Matcher m = ELEMENT.matcher(svg);
        while (m.find()) {
            Map<String, String> a = new LinkedHashMap<>();
            Matcher am = ATTRIBUTE.matcher(m.group(2));
            while (am.find()) {
                a.put(am.group(1), am.group(2));
            }
            java.awt.Shape geometry =
                    switch (m.group(1)) {
                        case "rect" -> {
                            double rx = num(a, "rx", 0);
                            yield new RoundRectangle2D.Double(
                                    num(a, "x", 0),
                                    num(a, "y", 0),
                                    num(a, "width", 0),
                                    num(a, "height", 0),
                                    2 * rx,
                                    2 * rx);
                        }
                        case "circle" -> {
                            double r = num(a, "r", 0);
                            yield new Ellipse2D.Double(
                                    num(a, "cx", 0) - r, num(a, "cy", 0) - r, 2 * r, 2 * r);
                        }
                        default -> path(a.getOrDefault("d", ""));
                    };
            shapes.add(
                    new Shape(
                            geometry,
                            color(a.get("fill")),
                            color(a.get("stroke")),
                            (float) num(a, "stroke-width", 0)));
        }
        return shapes;
    }

    private static Path2D path(String d) {
        Path2D.Double p = new Path2D.Double();
        String[] tokens = d.trim().split("[\\s,]+");
        int i = 0;
        while (i < tokens.length) {
            String cmd = tokens[i++];
            switch (cmd) {
                case "M" -> p.moveTo(d(tokens, i++), d(tokens, i++));
                case "L" -> p.lineTo(d(tokens, i++), d(tokens, i++));
                case "Q" -> {
                    p.quadTo(d(tokens, i), d(tokens, i + 1), d(tokens, i + 2), d(tokens, i + 3));
                    i += 4;
                }
                case "C" -> {
                    p.curveTo(
                            d(tokens, i),
                            d(tokens, i + 1),
                            d(tokens, i + 2),
                            d(tokens, i + 3),
                            d(tokens, i + 4),
                            d(tokens, i + 5));
                    i += 6;
                }
                case "Z", "z" -> p.closePath();
                default -> throw new IllegalArgumentException("Unsupported path command: " + cmd);
            }
        }
        return p;
    }

    private static double d(String[] tokens, int i) {
        return Double.parseDouble(tokens[i]);
    }

    private static double num(Map<String, String> a, String name, double fallback) {
        String v = a.get(name);
        return v == null ? fallback : Double.parseDouble(v);
    }

    private static Color color(String value) {
        if (value == null || value.equals("none")) {
            return null;
        }
        if (!value.matches("#[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("Only #RRGGBB colours are supported: " + value);
        }
        return new Color(Integer.parseInt(value.substring(1), 16));
    }

    private static BufferedImage render(List<Shape> shapes, double viewSize, int size) {
        // Small sizes are drawn at 4x and scaled down for smoother anti-aliasing.
        int scale = size < 64 ? 4 : 1;
        int canvas = size * scale;
        BufferedImage img = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.transform(AffineTransform.getScaleInstance(canvas / viewSize, canvas / viewSize));
        for (Shape s : shapes) {
            if (s.fill() != null) {
                g.setColor(s.fill());
                g.fill(s.geometry());
            }
            if (s.stroke() != null) {
                g.setColor(s.stroke());
                g.setStroke(
                        new BasicStroke(
                                s.strokeWidth(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(s.geometry());
            }
        }
        g.dispose();
        if (scale == 1) {
            return img;
        }
        BufferedImage small = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = small.createGraphics();
        sg.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        sg.drawImage(img.getScaledInstance(size, size, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
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
    private static byte[] icns(Map<Integer, byte[]> pngs, List<Shape> shapes, double viewSize)
            throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : ICNS_TYPES.entrySet()) {
            byte[] png = pngs.get(e.getValue());
            entries.put(e.getKey(), png != null ? png : toPng(render(shapes, viewSize, e.getValue())));
        }
        int total = 8;
        for (byte[] png : entries.values()) {
            total += 8 + png.length;
        }
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        b.put("icns".getBytes(StandardCharsets.US_ASCII)).putInt(total);
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            b.put(e.getKey().getBytes(StandardCharsets.US_ASCII)).putInt(8 + e.getValue().length);
            b.put(e.getValue());
        }
        return b.array();
    }

    @SuppressWarnings("unused")
    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
