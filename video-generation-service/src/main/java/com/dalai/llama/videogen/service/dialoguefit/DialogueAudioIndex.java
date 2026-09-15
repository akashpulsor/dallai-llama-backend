package com.dalai.llama.videogen.service.dialoguefit;

import com.dalai.llama.videogen.service.CloneAudioService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How long each already-synthesized line in a project actually runs, looked up by beat.
 *
 * <p>A project's dialogue is synthesized before any video is generated -- that is what the video
 * page's "Prepare all dialogues" does -- and every take is stored. So by the time a shot is being
 * prepared, the real length of its spoken audio is a fact already sitting in the database, and the
 * fit between line and clip can be settled against it instead of against a character count.
 *
 * <p>Built once per project and passed down, rather than queried per shot: preparing a project of
 * forty shots would otherwise run forty copies of the same small query. This mirrors the
 * {@code videoModelCatalog} / {@code maxPromptLengthCache} arguments already threaded through the
 * prepare path for the same reason.
 *
 * <p>Immutable once built, and deliberately not cached across requests: a creator who re-dubs a
 * line and immediately looks at the fit must see the new take's length, not the previous one's.
 */
@Slf4j
@Component
public class DialogueAudioIndex {

    private final CloneAudioService cloneAudioService;
    private final AudioDurationProbe durationProbe;

    public DialogueAudioIndex(CloneAudioService cloneAudioService, AudioDurationProbe durationProbe) {
        this.cloneAudioService = cloneAudioService;
        this.durationProbe = durationProbe;
    }

    /**
     * The measured length of every take in this project, keyed by dialogue-beat id.
     *
     * <p>Takes saved before lengths were recorded are probed once here and written back, so a
     * project converges on stored measurements as its shots are opened -- see
     * {@link CloneAudioService#backfillDuration}. A take that cannot be probed is left out of the
     * map entirely rather than entered as zero: absent means "not known", which callers turn into a
     * labelled estimate, whereas zero would read as a silent take that fits any shot.
     */
    public Index forProject(UUID tenantId, UUID projectId) {
        Map<UUID, BigDecimal> byBeat = new HashMap<>();
        Map<UUID, BigDecimal> byShot = new HashMap<>();
        Map<UUID, Integer> charsByBeat = new HashMap<>();
        Map<UUID, Integer> charsByShot = new HashMap<>();
        Map<UUID, String> textByBeat = new HashMap<>();
        Map<UUID, String> textByShot = new HashMap<>();
        List<SpeakingRate.Take> rateTakes = new java.util.ArrayList<>();
        List<CloneAudioService.CloneAudioView> takes;
        try {
            takes = cloneAudioService.list(tenantId, projectId);
        } catch (RuntimeException ex) {
            // The fit report degrades to estimates; it must not take the prepare down with it.
            log.warn("Could not read synthesized dialogue for project {}: {}", projectId, ex.getMessage());
            return Index.empty();
        }
        for (CloneAudioService.CloneAudioView take : takes) {
            double seconds = take.durationMs() != null
                    ? take.durationMs() / 1000.0
                    : probeAndRemember(tenantId, projectId, take);
            if (seconds <= 0) {
                continue;
            }
            BigDecimal value = BigDecimal.valueOf(seconds).setScale(3, RoundingMode.HALF_UP);
            // The spoken text alongside its length is what makes a real speaking rate derivable --
            // see SpeakingRate for why a configured constant is not good enough.
            int characters = take.text() == null ? 0 : take.text().trim().length();
            if (characters > 0) {
                rateTakes.add(new SpeakingRate.Take(characters, seconds));
            }
            if (take.beatId() != null) {
                byBeat.put(take.beatId(), value);
                charsByBeat.put(take.beatId(), characters);
                textByBeat.put(take.beatId(), take.text() == null ? "" : take.text().trim());
            } else if (take.shotId() != null) {
                // A shot-level take: the whole line synthesized as one, for a shot with no beats
                // broken out. Kept separately so a beat lookup never silently resolves to it.
                byShot.put(take.shotId(), value);
                charsByShot.put(take.shotId(), characters);
                textByShot.put(take.shotId(), take.text() == null ? "" : take.text().trim());
            }
        }
        return new Index(byBeat, byShot, charsByBeat, charsByShot, textByBeat, textByShot,
                List.copyOf(rateTakes));
    }

    private double probeAndRemember(UUID tenantId, UUID projectId, CloneAudioService.CloneAudioView take) {
        double seconds = durationProbe.probeUrl(take.audioUrl());
        if (seconds > 0) {
            UUID resourceId = take.beatId() != null ? take.beatId() : take.shotId();
            cloneAudioService.backfillDuration(tenantId, projectId, resourceId, seconds);
        }
        return seconds;
    }

    /** A project's measured take lengths. Empty is a normal state -- nothing has been dubbed yet. */
    public record Index(Map<UUID, BigDecimal> byBeatId, Map<UUID, BigDecimal> byShotId,
                        Map<UUID, Integer> charsByBeatId, Map<UUID, Integer> charsByShotId,
                        Map<UUID, String> textByBeatId, Map<UUID, String> textByShotId,
                        List<SpeakingRate.Take> takes) {

        public static Index empty() {
            return new Index(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
        }

        /**
         * True when the stored take was synthesized from exactly this text.
         *
         * <p>The check that makes a measurement honest. A take is stored per beat, so rewriting a
         * line leaves the PREVIOUS take sitting under the same key -- right voice, right shot, wrong
         * words. Reading its length as a measurement of the new line is worse than having no
         * measurement at all: the report would state 4.32s with full confidence for a line nobody
         * has ever spoken. Until the shot is dubbed again, a rewritten line is estimated, and says
         * so. {@code CloneVoiceService.listSavedAudio} makes the same comparison for the same
         * reason; this index reads the table directly, so it has to make it too.
         */
        private boolean takeMatches(String stored, String currentText) {
            if (stored == null) {
                return false;
            }
            // A caller that does not know the current text gets the take as-is -- it has nothing to
            // compare against, and refusing every measurement would be worse.
            return currentText == null || stored.equals(currentText.trim());
        }

        /** How fast this project's voice actually speaks, for estimating lines that have not been
         * synthesized yet. Falls back to {@code configuredCharsPerSecond} only when the project has
         * no measured take at all. */
        public SpeakingRate speakingRate(double configuredCharsPerSecond) {
            return SpeakingRate.fromProject(takes, configuredCharsPerSecond);
        }

        /** The rate measured from one beat's own take -- the most trustworthy figure available,
         * because nothing about it is generalised. Null when that beat has not been synthesized. */
        public SpeakingRate rateForBeat(UUID beatId) {
            return rateFrom(measuredForBeat(beatId), charsByBeatId.get(beatId));
        }

        public SpeakingRate rateForShot(UUID shotId) {
            return rateFrom(measuredForShot(shotId), charsByShotId.get(shotId));
        }

        private SpeakingRate rateFrom(BigDecimal seconds, Integer characters) {
            if (seconds == null || characters == null || characters <= 0 || seconds.signum() <= 0) {
                return null;
            }
            return SpeakingRate.fromLine(characters, seconds.doubleValue());
        }

        /** Null when this beat has no measured take, or when the take it has was synthesized from
         * different words -- see {@link #takeMatches}. Callers must not substitute the planned
         * length here; they need to know the difference to report it honestly. */
        public BigDecimal measuredForBeat(UUID beatId, String currentText) {
            if (beatId == null || !takeMatches(textByBeatId.get(beatId), currentText)) {
                return null;
            }
            return byBeatId.get(beatId);
        }

        public BigDecimal measuredForShot(UUID shotId, String currentText) {
            if (shotId == null || !takeMatches(textByShotId.get(shotId), currentText)) {
                return null;
            }
            return byShotId.get(shotId);
        }

        /** Without the current text -- for callers that only need "was anything ever recorded here",
         * such as the rate lookup, where a superseded take is still a valid sample of how fast this
         * voice speaks even though its length no longer describes the line. */
        public BigDecimal measuredForBeat(UUID beatId) {
            return measuredForBeat(beatId, null);
        }

        public BigDecimal measuredForShot(UUID shotId) {
            return measuredForShot(shotId, null);
        }

        public boolean isEmpty() {
            return byBeatId.isEmpty() && byShotId.isEmpty();
        }
    }
}
