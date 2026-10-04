package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.domain.ShotActionKind;
import com.dalai.llama.videogen.dto.generationplan.RequiredActionView;
import com.dalai.llama.videogen.dto.shotcontext.Camera;
import com.dalai.llama.videogen.dto.shotcontext.DialogueBeat;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The actions an approved shot plan requires, numbered once.
 *
 * <p>Deterministic on purpose. The shot plan holds its action as prose ({@code action} and the
 * script line, joined by assembly into the narrative), so the obvious move is to ask a model to
 * list the actions -- and a model asked to list actions will merge two, drop a small one or add
 * one it inferred, which is exactly what every later check exists to catch. Splitting the plan's
 * own sentences cannot invent anything, and gives every step after it the same identifiers.
 *
 * <p>Identifiers: {@code OPEN} and {@code END} bracket every shot; {@code A1..An} are the plan's
 * action sentences in order; {@code D1..Dn} its lines of dialogue (FIXED timing when the line has a
 * measured or planned length); {@code C1} the planned camera movement. Each action depends on the
 * one before it in its own track, so the order the plan wrote is the order every check enforces.
 */
@Component
public class RequiredActionExtractor {

    public static final String OPEN = "OPEN";
    public static final String END = "END";

    /** A sentence ends at . ! or ? followed by whitespace and a capital, quote or bracket -- so
     * "85mm at T2.8" and "1.5 seconds" stay whole. */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=[\"'(\\p{Lu}])");

    public List<RequiredActionView> extract(ShotContext shot) {
        List<RequiredActionView> actions = new ArrayList<>();
        actions.add(new RequiredActionView(OPEN, ShotActionKind.OPENING_STATE, openingState(shot), null, null));

        String previous = OPEN;
        int n = 1;
        for (String sentence : actionSentences(shot)) {
            String id = "A" + n++;
            actions.add(new RequiredActionView(id, ShotActionKind.ACTION, sentence, null, previous));
            previous = id;
        }
        String lastAction = previous;

        String previousLine = OPEN;
        int d = 1;
        for (Line line : lines(shot)) {
            String id = "D" + d++;
            actions.add(new RequiredActionView(id, ShotActionKind.DIALOGUE, line.description(), line.seconds(), previousLine));
            previousLine = id;
        }

        String camera = cameraMovement(shot.camera());
        if (camera != null) {
            actions.add(new RequiredActionView("C1", ShotActionKind.CAMERA, camera, null, OPEN));
        }

        String endsAfter = lastAction.equals(OPEN) ? (previousLine.equals(OPEN) ? OPEN : previousLine) : lastAction;
        String endsAfterText = actions.stream().filter(a -> a.actionId().equals(endsAfter))
                .findFirst().map(RequiredActionView::description).orElse("");
        actions.add(new RequiredActionView(END, ShotActionKind.ENDING_STATE,
                endsAfter.equals(OPEN)
                        ? "Final state: the frame as opened, held to the last frame."
                        : "Final state: the result of " + endsAfter + " is complete and held to the last frame (" + endsAfterText + ")",
                null, endsAfter));
        return actions;
    }

    /** The plan's action sentences in order, each once. Repeating a sentence restates an action;
     * it does not add one. */
    private List<String> actionSentences(ShotContext shot) {
        String text = shot.narrative() == null ? null : shot.narrative().scriptLine();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> sentences = new ArrayList<>();
        for (String raw : SENTENCE_BREAK.split(text.strip())) {
            String sentence = raw.strip();
            String key = sentence.replaceAll("[\\s.!?]+$", "").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (!key.isEmpty() && seen.add(key)) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private record Line(String description, BigDecimal seconds) {
    }

    /** Beats when the shot has them -- each with the length it actually takes to say -- else the
     * shot's single line with no length, since nothing has measured it. */
    private List<Line> lines(ShotContext shot) {
        List<DialogueBeat> beats = shot.dialogueBeats() == null ? List.of() : shot.dialogueBeats();
        List<Line> lines = new ArrayList<>();
        beats.stream()
                .filter(beat -> beat.text() != null && !beat.text().isBlank())
                .sorted(Comparator.comparing(beat -> beat.startSeconds() == null ? BigDecimal.ZERO : beat.startSeconds()))
                .forEach(beat -> {
                    String who = beat.characterKey() == null || beat.characterKey().isBlank() ? "A speaker" : beat.characterKey();
                    BigDecimal seconds = beat.effectiveSeconds();
                    lines.add(new Line(who + " says: \"" + beat.text().strip() + "\"",
                            seconds == null || seconds.signum() <= 0 ? null : seconds.setScale(3, RoundingMode.HALF_UP)));
                });
        if (lines.isEmpty() && shot.narrative() != null && shot.narrative().dialogue() != null
                && !shot.narrative().dialogue().isBlank()) {
            lines.add(new Line("Spoken line: \"" + shot.narrative().dialogue().strip() + "\"", null));
        }
        return lines;
    }

    private String cameraMovement(Camera camera) {
        if (camera == null || camera.movementType() == null || camera.movementType().isBlank()) {
            return null;
        }
        return "Camera: " + joined(camera.movementType(), camera.movementTrajectory(), camera.movementSpeed());
    }

    /** How the frame stands at 0.0s, in the plan's own fields. */
    private String openingState(ShotContext shot) {
        Camera camera = shot.camera();
        String composition = camera == null ? null : joined(
                camera.shotSize() == null ? null : camera.shotSize().name().replace('_', ' ').toLowerCase(Locale.ROOT),
                camera.framing(), camera.subjectPlacement());
        String location = shot.environment() == null ? null : shot.environment().location();
        String staged = joined(composition, location);
        return staged == null
                ? "Opening state: the frame as staged in the shot plan at 0.0s."
                : "Opening state at 0.0s: " + staged + ".";
    }

    private static String joined(String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part.strip());
            }
        }
        return present.isEmpty() ? null : String.join(", ", present);
    }
}
