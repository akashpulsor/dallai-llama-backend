package com.dalai.llama.preprod.service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * An image as it is sent to an image model: no larger than the model works at, typed by its
 * actual bytes.
 *
 * <p>Frame generation sends several images in one request -- the frame being edited, each
 * character's face photo, the product photo. They were sent at full size, base64'd, so a few
 * phone photos (4-8 MB each) pushed the request past Gemini's 20 MB limit and the call failed,
 * every retry alike. The type was also guessed from the file name, so a ".jpg" that was really a
 * PNG went out mislabelled.
 */
public final class ModelImage {

    /** Longest side sent to the model; larger only costs request size, the model works below it. */
    static final int MAX_SIDE = 1536;

    /** An image already this small and this size is sent untouched. */
    static final int MAX_UNTOUCHED_BYTES = 1_500_000;

    private ModelImage() {}

    /** A data URI for the model, or null for empty input. Bytes Java cannot decode (e.g. WebP)
     * go through as they are, typed by their header. */
    public static String dataUri(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        BufferedImage image = read(bytes);
        if (image == null || (fits(image) && bytes.length <= MAX_UNTOUCHED_BYTES)) {
            return encode(sniffMimeType(bytes), bytes);
        }
        BufferedImage scaled = fits(image) ? image : scale(image);
        return image.getColorModel().hasAlpha()
                ? encode("image/png", png(scaled))
                : encode("image/jpeg", jpeg(scaled));
    }

    /** The type the bytes really are, from their magic number; JPEG when unrecognised. */
    static String sniffMimeType(byte[] bytes) {
        if (startsWith(bytes, 0x89, 0x50, 0x4E, 0x47)) return "image/png";
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (bytes.length >= 12 && startsWith(bytes, 0x52, 0x49, 0x46, 0x46)
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        if (startsWith(bytes, 0x47, 0x49, 0x46)) return "image/gif";
        return "image/jpeg";
    }

    private static boolean fits(BufferedImage image) {
        return Math.max(image.getWidth(), image.getHeight()) <= MAX_SIDE;
    }

    private static BufferedImage scale(BufferedImage image) {
        double factor = (double) MAX_SIDE / Math.max(image.getWidth(), image.getHeight());
        int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(image.getHeight() * factor));
        int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage out = new BufferedImage(width, height, type);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return out;
    }

    private static byte[] jpeg(BufferedImage image) {
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(rgb, null, null), param);
            stream.flush();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not encode an image for the model: " + ex.getMessage(), ex);
        } finally {
            writer.dispose();
        }
    }

    private static byte[] png(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not encode an image for the model: " + ex.getMessage(), ex);
        }
    }

    private static BufferedImage read(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String encode(String mimeType, byte[] bytes) {
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }
}
