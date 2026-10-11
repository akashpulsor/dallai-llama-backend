package com.dalai.llama.tenant.leadmanagement.inquiry;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Images a brand attached to a request (V41). */
@Repository
@RequiredArgsConstructor
public class InquiryImageStore {

    public static final int MAX_PER_KIND = 4;
    public static final long MAX_BYTES = 3L * 1024 * 1024;

    public enum Kind { PRODUCT, REFERENCE }

    public record ImageRef(UUID id, Kind kind, String fileName) {
    }

    public record Image(UUID id, Kind kind, String fileName, String contentType, byte[] bytes) {
    }

    private final JdbcTemplate jdbc;

    /** Checks then stores; nothing stored when any file is refused. */
    public void save(UUID inquiryId, List<MultipartFile> product, List<MultipartFile> reference) {
        validate(product);
        validate(reference);
        store(inquiryId, Kind.PRODUCT, product);
        store(inquiryId, Kind.REFERENCE, reference);
    }

    public static void validate(List<MultipartFile> files) {
        List<MultipartFile> present = present(files);
        if (present.size() > MAX_PER_KIND) throw new IllegalArgumentException("Attach at most " + MAX_PER_KIND + " images of each kind");
        for (MultipartFile f : present) {
            String type = f.getContentType() == null ? "" : f.getContentType().toLowerCase(Locale.ROOT);
            if (!type.startsWith("image/")) throw new IllegalArgumentException("Only images can be attached");
            if (f.getSize() > MAX_BYTES) throw new IllegalArgumentException("Each image can be at most 3 MB");
        }
    }

    public List<ImageRef> refs(UUID inquiryId) {
        return jdbc.query("SELECT id, kind, file_name FROM lead_brand_inquiry_image WHERE inquiry_id = ? ORDER BY kind, created_at, id",
                (rs, n) -> new ImageRef(rs.getObject(1, UUID.class), Kind.valueOf(rs.getString(2)), rs.getString(3)), inquiryId);
    }

    public List<Image> all(UUID inquiryId) {
        return jdbc.query("SELECT id, kind, file_name, content_type, bytes FROM lead_brand_inquiry_image WHERE inquiry_id = ? ORDER BY kind, created_at, id",
                (rs, n) -> new Image(rs.getObject(1, UUID.class), Kind.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getBytes(5)),
                inquiryId);
    }

    public Optional<Image> one(UUID inquiryId, UUID imageId) {
        return all(inquiryId).stream().filter(i -> i.id().equals(imageId)).findFirst();
    }

    private void store(UUID inquiryId, Kind kind, List<MultipartFile> files) {
        for (MultipartFile f : present(files)) {
            try {
                String name = f.getOriginalFilename() == null ? null : f.getOriginalFilename().replaceAll("[^A-Za-z0-9._ -]", "_");
                jdbc.update("INSERT INTO lead_brand_inquiry_image (id, inquiry_id, kind, file_name, content_type, bytes) VALUES (?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), inquiryId, kind.name(), name == null ? null : name.substring(0, Math.min(160, name.length())),
                        f.getContentType(), f.getBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static List<MultipartFile> present(List<MultipartFile> files) {
        return files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
    }
}
