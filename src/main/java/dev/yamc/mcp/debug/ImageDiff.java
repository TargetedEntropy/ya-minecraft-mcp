package dev.yamc.mcp.debug;

import com.mojang.blaze3d.platform.NativeImage;
import dev.yamc.mcp.YamcMcp;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.util.Mc;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Screenshot baselines for render regression checks: save a screenshot under a name, later compare a new one against
 * it pixel by pixel. Baselines are PNG files in the game folder's yamcmcp-baselines directory.
 */
public final class ImageDiff {
    private ImageDiff() {}

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final int DIFF_COLOR = 0xFFFF0000;

    /**
     * @param differing pixels where some channel differs by more than the threshold
     * @param diffPng   the screenshot dimmed to grey with differing pixels in red, or null when nothing differs
     */
    public record Result(int width, int height, long differing, int maxDelta, byte @Nullable [] diffPng) {
        public double ratio() {
            return (double) differing / ((long) width * height);
        }
    }

    public static Path file(String name) {
        if (!NAME.matcher(name).matches()) throw new ToolException("Baseline names are 1-64 letters, digits, '_', '-' or '.'.");
        return YamcMcp.platform().gameDir().resolve("yamcmcp-baselines").resolve(name + ".png");
    }

    public static Path save(String name, byte[] png) throws IOException {
        Path f = file(name);
        Files.createDirectories(f.getParent());
        Files.write(f, png);
        return f;
    }

    public static Result compare(NativeImage current, String name, int threshold) throws IOException {
        Path f = file(name);
        if (!Files.isRegularFile(f)) throw new ToolException("No baseline '" + name + "'. Save one first with save_baseline.");
        try (InputStream in = Files.newInputStream(f); NativeImage base = NativeImage.read(in)) {
            int w = current.getWidth(), h = current.getHeight();
            if (base.getWidth() != w || base.getHeight() != h)
                throw new ToolException("Baseline '" + name + "' is " + base.getWidth() + "x" + base.getHeight() + " but this screenshot is " + w + "x" + h
                        + ": use the same max_width and window size.");
            long differing = 0;
            int maxDelta = 0;
            try (NativeImage diff = new NativeImage(w, h, false)) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int a = Mc.argb(current, x, y), b = Mc.argb(base, x, y);
                        int d = Math.max(Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)),
                                Math.max(Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)), Math.abs((a & 255) - (b & 255))));
                        maxDelta = Math.max(maxDelta, d);
                        if (d > threshold) {
                            differing++;
                            Mc.setArgb(diff, x, y, DIFF_COLOR);
                        } else {
                            int grey = (((a >> 16) & 255) * 3 + ((a >> 8) & 255) * 6 + (a & 255)) / 30; // dimmed luminance
                            Mc.setArgb(diff, x, y, 0xFF000000 | grey << 16 | grey << 8 | grey);
                        }
                    }
                }
                return new Result(w, h, differing, maxDelta, differing == 0 ? null : Mc.png(diff));
            }
        }
    }
}
