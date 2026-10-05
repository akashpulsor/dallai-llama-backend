package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.entity.Shot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A step shot packed for an outside image tool: {@code prompt.txt}, the attached images numbered in
 * send order (the prompt refers to them by position), and a README saying how to bring the result
 * back. Built from the same request {@link ShotImageService} sends, so the two can't drift. */
public record ShotImageBundle(String fileName, byte[] zip) {

    static ShotImageBundle of(Shot shot, Shot source, ShotImageKind kind, String modelId, String prompt,
                              List<String> imageDataUris, List<String> labels, Map<String, Object> params) {
        List<String> images = imageDataUris == null ? List.of() : imageDataUris;
        String name = "shot-" + slug(label(shot)) + "-step-from-" + slug(label(source)) + "-" + kind.name().toLowerCase();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            put(zip, "prompt.txt", prompt.getBytes(StandardCharsets.UTF_8));
            StringBuilder order = new StringBuilder();
            for (int i = 0; i < images.size(); i++) {
                String label = i < labels.size() ? labels.get(i) : "image";
                String file = "%02d-%s.%s".formatted(i + 1, slug(label), extension(images.get(i)));
                put(zip, "images/" + file, bytes(images.get(i)));
                order.append("  ").append(i + 1).append(". images/").append(file).append("  (").append(label).append(")\n");
            }
            put(zip, "README.txt", readme(shot, source, kind, modelId, order.toString(), params).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw PreProductionException.upstream("Could not build the step bundle: " + ex.getMessage(), ex);
        }
        return new ShotImageBundle(name + ".zip", buffer.toByteArray());
    }

    private static String readme(Shot shot, Shot source, ShotImageKind kind, String modelId, String order,
                                 Map<String, Object> params) {
        StringBuilder text = new StringBuilder()
                .append("Step shot: shot ").append(label(shot)).append(" as the next frame of shot ").append(label(source))
                .append(" (").append(kind).append(" image)\n\n")
                .append("Model the app uses: ").append(modelId).append("\n\n")
                .append("1. Paste prompt.txt as the instruction.\n")
                .append("2. Attach the images in this order -- the prompt refers to them by position (\"the first attached image\"):\n");
        text.append(order.isEmpty() ? "  (none -- see reference URLs below)\n" : order);
        Object referenceUrls = params == null ? null : params.get("reference_image_urls");
        if (referenceUrls instanceof List<?> urls && !urls.isEmpty()) {
            text.append("\nReference images this model takes as URLs (links expire after an hour):\n");
            urls.forEach(url -> text.append("  ").append(url).append('\n'));
        }
        return text.append("\n3. Bring the result back: in the app open shot ").append(label(shot))
                .append(", ").append(kind).append(" tile -> Upload -> \"Same -- use this exact image\".\n")
                .toString();
    }

    private static void put(ZipOutputStream zip, String path, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content);
        zip.closeEntry();
    }

    /** data:[mime];base64,[payload] -> payload bytes. */
    static byte[] bytes(String dataUri) {
        return Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1));
    }

    static String extension(String dataUri) {
        String mime = dataUri.startsWith("data:") ? dataUri.substring(5, dataUri.indexOf(';')) : "";
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
    }

    static String slug(String label) {
        String slug = label.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "image" : slug;
    }

    private static String label(Shot shot) {
        return shot.getShotRef() != null && !shot.getShotRef().isBlank() ? shot.getShotRef() : String.valueOf(shot.getShotNumber());
    }
}
