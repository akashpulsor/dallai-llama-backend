package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import com.dalai.llama.tenant.showcase.domain.VideoHostType;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.dto.MyShowcaseItemView;
import com.dalai.llama.tenant.showcase.dto.UploadShowcaseFilmRequest;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A video the creator uploads straight to their profile -- usually one that is private on their
 * YouTube channel, downloaded from YouTube Studio. YouTube has no API to fetch the file, so the
 * creator brings it. It shows as External, plays from our copy in our own player, and its
 * thumbnail is the frame (or image) the creator picked.
 */
@Service
@RequiredArgsConstructor
public class ShowcaseUploadService {

    static final long MAX_VIDEO_BYTES = 1024L * 1024 * 1024;
    static final long MAX_POSTER_BYTES = 3L * 1024 * 1024;
    static final int MAX_TITLE = 100;
    private static final Map<String, String> VIDEO_TYPES = Map.of("video/mp4", "mp4", "video/quicktime", "mov", "video/webm", "webm");
    private static final Map<String, String> POSTER_TYPES = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    private final CreatorProfileService profileService;
    private final ShowcaseItemRepository itemRepository;
    private final ShowcaseMediaStore mediaStore;
    private final PublicIdGenerator publicIds;
    private final ShowcasePosterLinks posterLinks;
    private final Clock clock;

    @Transactional
    public MyShowcaseItemView upload(UUID tenantId, MultipartFile video, MultipartFile poster, UploadShowcaseFilmRequest request) {
        String videoExt = check(video, VIDEO_TYPES, MAX_VIDEO_BYTES, "Upload an MP4, MOV or WebM video", "A video can be at most 1 GB");
        String posterExt = check(poster, POSTER_TYPES, MAX_POSTER_BYTES, "Pick a thumbnail: a frame of the video, or a JPG/PNG image", "A thumbnail can be at most 3 MB");
        String title = request.title() == null ? "" : request.title().trim();
        if (title.isEmpty() || title.length() > MAX_TITLE) throw new IllegalArgumentException("Give the video a title of up to " + MAX_TITLE + " characters");
        if (request.industry() == null || request.format() == null) throw new IllegalArgumentException("Pick the video's industry and format");
        if (!request.rightsConfirmed()) throw new IllegalArgumentException("Confirm the video is yours to show");
        if (request.durationSeconds() == null || request.durationSeconds().signum() <= 0
                || request.width() == null || request.width() <= 0 || request.height() == null || request.height() <= 0) {
            throw new IllegalArgumentException("The video's length and size couldn't be read -- try another file");
        }
        profileService.requireProfile(tenantId);

        UUID id = UUID.randomUUID();
        String prefix = "showcase-uploads/" + tenantId + "/" + id;
        String videoKey = prefix + "." + videoExt;
        String posterKey = prefix + "-poster." + posterExt;
        mediaStore.put(videoKey, video, video.getContentType().toLowerCase(Locale.ROOT));
        mediaStore.put(posterKey, poster, poster.getContentType().toLowerCase(Locale.ROOT));

        OffsetDateTime now = OffsetDateTime.now(clock);
        ShowcaseItem item = ShowcaseItem.builder()
                .id(id)
                .publicId(publicIds.next())
                .tenantId(tenantId)
                .publishedAt(now)
                .build();
        item.setOrigin(ShowcaseOrigin.EXTERNAL);
        item.setHost(VideoHostType.SELF);
        item.setUploadObjectKey(videoKey);
        item.setPosterObjectKey(posterKey);
        item.setTitleOverride(title);
        item.setFilmDurationSeconds(request.durationSeconds().setScale(2, java.math.RoundingMode.HALF_UP));
        item.setFilmWidth(request.width());
        item.setFilmHeight(request.height());
        item.setIndustry(request.industry());
        item.setFormat(request.format());
        String clientLabel = request.clientLabel() == null ? "" : request.clientLabel().trim();
        item.setClientLabel(clientLabel.isEmpty() ? null : clientLabel.substring(0, Math.min(80, clientLabel.length())));
        item.setRightsConfirmedAt(now);
        item.setStatus(ShowcaseItemStatus.LIVE);
        item.setSortOrder((int) itemRepository.countByTenantIdAndStatus(tenantId, ShowcaseItemStatus.LIVE));
        return ShowcasePickService.toView(itemRepository.save(item), null, posterLinks.of(item));
    }

    /** The file's extension by its type, or the reason it is refused. */
    private static String check(MultipartFile file, Map<String, String> types, long maxBytes, String typeMessage, String sizeMessage) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException(typeMessage);
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String ext = types.get(type);
        if (ext == null) throw new IllegalArgumentException(typeMessage);
        if (file.getSize() > maxBytes) throw new IllegalArgumentException(sizeMessage);
        return ext;
    }
}
