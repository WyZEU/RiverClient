package dev.wyz.clientcore.branding;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Composites the small River logo into the corner of Minecraft's window icon so the
 * taskbar/window icon reads as River without losing the familiar Minecraft icon.
 * Pure javax.imageio, so it works off the render thread and needs no GL.
 */
public final class IconBrander {
    private IconBrander() {}

    private static BufferedImage cachedLogo;
    private static boolean logoLoaded;

    /** Reads the vanilla icon PNG, overlays the River logo bottom-right, returns a PNG stream. */
    public static InputStream brand(InputStream baseStream) throws IOException {
        byte[] baseBytes;
        try (InputStream in = baseStream) {
            baseBytes = in.readAllBytes();
        }
        BufferedImage base = ImageIO.read(new ByteArrayInputStream(baseBytes));
        BufferedImage logo = logo();
        if (base == null || logo == null) {
            return new ByteArrayInputStream(baseBytes);
        }

        int w = base.getWidth();
        int h = base.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(base, 0, 0, null);

        int size = Math.round(w * 0.55f);
        g.drawImage(logo, w - size, h - size, size, size, null);
        g.dispose();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "PNG", bos);
        return new ByteArrayInputStream(bos.toByteArray());
    }

    private static synchronized BufferedImage logo() {
        if (!logoLoaded) {
            logoLoaded = true;
            try (InputStream in = IconBrander.class.getResourceAsStream("/assets/clientcore/textures/watermark_logo.png")) {
                if (in != null) cachedLogo = ImageIO.read(in);
            } catch (IOException ignored) {
            }
        }
        return cachedLogo;
    }
}
