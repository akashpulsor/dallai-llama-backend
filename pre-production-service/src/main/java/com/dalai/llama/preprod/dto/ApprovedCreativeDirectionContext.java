package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.ReferenceMediaType;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The project's creative contract as every downstream generation stage receives it: the original
 * idea and brief, plus the approved director's treatment and the client references it draws on.
 * Resolved once per stage by {@code CreativeDirectionContextService}; no stage reads the
 * creative-direction tables itself. Text stages get {@link #promptBlock()}; image stages also use
 * {@link #references()} to attach the client's reference images through their existing paths.
 */
public record ApprovedCreativeDirectionContext(
        UUID directionId,
        int version,
        Idea idea,
        String briefText,
        String title,
        String creativeConcept,
        String directorsTreatment,
        String storytellingStyle,
        String storyPeriod,
        String colorTreatment,
        String contrast,
        String texture,
        String overallAesthetic,
        String cinematographyPhilosophy,
        String emotionalJourney,
        String soundDirection,
        String signatureCreativeDevice,
        String creativeRationale,
        List<Reference> references
) {

    /** What a template receives for {{creativeDirection}} on a project with no approved direction
     * (projects created before Creative Direction existed). Templates read it as "none given". */
    public static final String NONE_APPROVED =
            "No creative direction has been approved for this project. Work from the idea and brief alone.";

    public record Idea(String title, String concept, String targetAudience, String campaignAngle,
                       String keyMessage, String tone) {}

    public record Reference(UUID assetId, ReferenceMediaType mediaType, String bucket, String objectKey,
                            String clientInstruction, String referenceAnalysis) {}

    /** The text every LLM stage gets for {{creativeDirection}}. */
    public String promptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("ORIGINAL IDEA\n");
        if (idea != null) {
            line(sb, "Title", idea.title());
            line(sb, "Concept", idea.concept());
            line(sb, "Target audience", idea.targetAudience());
            line(sb, "Campaign angle", idea.campaignAngle());
            line(sb, "Key message", idea.keyMessage());
            line(sb, "Tone", idea.tone());
        }
        line(sb, "Brief", briefText);
        sb.append("\nAPPROVED CREATIVE DIRECTION: \"").append(title).append("\" (version ").append(version).append(")\n");
        line(sb, "Creative concept", creativeConcept);
        line(sb, "Director's treatment", directorsTreatment);
        line(sb, "Storytelling style", storytellingStyle);
        String visual = joined("; ",
                labelled("story period", storyPeriod), labelled("colour treatment", colorTreatment),
                labelled("contrast", contrast), labelled("texture", texture), labelled("overall aesthetic", overallAesthetic));
        line(sb, "Visual language", visual);
        line(sb, "Cinematography philosophy", cinematographyPhilosophy);
        line(sb, "Emotional journey", emotionalJourney);
        line(sb, "Sound direction", soundDirection);
        line(sb, "Signature creative device", signatureCreativeDevice);
        line(sb, "Creative rationale", creativeRationale);
        if (references != null && !references.isEmpty()) {
            sb.append("\nCLIENT REFERENCES THIS DIRECTION DRAWS ON\n");
            for (Reference reference : references) {
                sb.append("- ").append(reference.mediaType()).append(' ').append(reference.assetId());
                String notes = joined("; ", labelled("client instruction", reference.clientInstruction()),
                        labelled("analysis", reference.referenceAnalysis()));
                sb.append(notes.isEmpty() ? "" : ": " + notes).append('\n');
            }
        }
        return sb.toString().strip();
    }

    /** The approved look as a production still needs it -- the visual decisions only. */
    public String visualDirectionBlock() {
        return joined("\n",
                labelled("Story period", storyPeriod), labelled("Colour treatment", colorTreatment),
                labelled("Contrast", contrast), labelled("Texture", texture), labelled("Overall aesthetic", overallAesthetic),
                labelled("Cinematography philosophy", cinematographyPhilosophy));
    }

    public List<Reference> imageReferences() {
        return references == null ? List.of() : references.stream()
                .filter(reference -> reference.mediaType() == ReferenceMediaType.IMAGE)
                .filter(reference -> reference.bucket() != null && reference.objectKey() != null)
                .toList();
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value.strip()).append('\n');
        }
    }

    private static String labelled(String label, String value) {
        return value == null || value.isBlank() ? null : label + ": " + value.strip();
    }

    private static String joined(String separator, String... parts) {
        return Stream.of(parts).filter(Objects::nonNull).collect(Collectors.joining(separator));
    }
}
