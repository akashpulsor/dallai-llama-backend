package com.dalai.llama.tenant.showcase.inspiration;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** The Inspiration board: public to read, curated by ops (internal only, like the other admin
 * endpoints). The public board lists only items whose picture is known: they play in our own
 * player, never a third-party embed. */
@RestController
@RequiredArgsConstructor
public class InspirationControllers {

    private final InspirationStore store;
    private final InspirationPosters posters;

    public record AddPinRequest(@NotBlank String pinLink, @NotBlank @Size(max = 120) String title,
                                @NotNull ShowcaseIndustry industry, @Size(max = 300) String note, Integer sortOrder,
                                /* Pinterest's oEmbed "height" for the pin, to size its card. */
                                @jakarta.validation.constraints.Min(100) @jakarta.validation.constraints.Max(2000) Integer embedHeight) {
    }

    @GetMapping("/api/v1/public/showcase/inspiration")
    public ResponseEntity<List<InspirationItem>> board(@RequestParam(required = false) ShowcaseIndustry industry) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(store.active(industry).stream().filter(i -> i.posterUrl() != null).toList());
    }

    @PostMapping("/api/v1/internal/admin/tenants/inspiration")
    public InspirationItem add(@Valid @RequestBody AddPinRequest request) {
        InspirationItem item = store.save(InspirationPins.idFrom(request.pinLink()), request.title().trim(), request.industry(),
                request.note() == null || request.note().isBlank() ? null : request.note().trim(),
                request.sortOrder() == null ? 0 : request.sortOrder(), request.embedHeight());
        // Its picture and video now, so it shows straight away (else InspirationMediaJob fills them).
        return posters.fetch(item.pinId()).map(poster -> {
            store.setPoster(item.id(), poster);
            return store.active(item.industry()).stream().filter(i -> i.id().equals(item.id())).findFirst().orElse(item);
        }).orElse(item);
    }

    @DeleteMapping("/api/v1/internal/admin/tenants/inspiration/{id}")
    public ResponseEntity<Void> hide(@PathVariable UUID id) {
        return store.hide(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
