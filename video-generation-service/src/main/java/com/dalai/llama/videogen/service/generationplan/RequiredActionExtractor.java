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
 *
 * <p>A motion graphic's actions are its animation notes -- how each element arrives, holds and
 * leaves -- not the script line, which for a graphic only names what it is. Its on-screen text is an
 * action of its own, quoted exactly, so the timeline gives it a moment and the checks notice a
 * graphic whose words never appear.
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

        List<Line> lines = lines(shot);
        String previous = OPEN;
        int n = 1;
        for (String sentence : actionSentences(shot, !lines.isEmpty())) {
            String id = "A" + n++;
            actions.add(new RequiredActionView(id, ShotActionKind.ACTION, sentence, null, previous));
            previous = id;
        }
        String text = onScreenText(shot);
        if (text != null) {
            // Its own track: the words can land at any point of the animation, so they are not
            // forced after the last element -- only required to appear.
            actions.add(new RequiredActionView("A" + n, ShotActionKind.ACTION,
                    "On-screen text appears, exactly as written: \"" + text + "\"", null, OPEN));
        }
        String lastAction = previous;

        String previousLine = OPEN;
        int d = 1;
        for (Line line : lines) {
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

    /**
     * The plan's action sentences in order, each once. A sentence that restates an earlier one --
     * the same words, or mostly the same content words ("The video opens with abstract lines..."
     * after "Abstract lines ... coalesce") -- restates an action; it does not add one. A quotation
     * is never split at its own "?" or ".", and when the shot has lines of dialogue a sentence that
     * quotes speech is that dialogue (its D action), not a visual action of its own.
     */
    private List<String> actionSentences(ShotContext shot, boolean hasDialogue) {
        String text = animationNotes(shot);
        if (text == null) {
            text = shot.narrative() == null ? null : shot.narrative().scriptLine();
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> sentences = new ArrayList<>();
        List<Set<String>> seen = new ArrayList<>();
        for (String sentence : splitOutsideQuotes(text.strip())) {
            if (hasDialogue && QUOTE.matcher(sentence).find()) {
                continue;
            }
            Set<String> words = contentWords(sentence);
            if (words.isEmpty() || seen.stream().anyMatch(earlier -> restates(earlier, words))) {
                continue;
            }
            seen.add(words);
            sentences.add(sentence);
        }
        return sentences;
    }

    /** A motion graphic's animation notes, or null for any other shot or a graphic without them. */
    private static String animationNotes(ShotContext shot) {
        if (!shot.isPlannedMotionGraphic()) return null;
        String notes = shot.motionGraphic().animationNotes();
        return notes == null || notes.isBlank() ? null : notes.strip();
    }

    /** A motion graphic's on-screen words, verbatim -- they are rendered as glyphs, so never reworded. */
    private static String onScreenText(ShotContext shot) {
        if (!shot.isPlannedMotionGraphic()) return null;
        String words = shot.motionGraphic().onScreenText();
        return words == null || words.isBlank() ? null : words.strip();
    }

    private static final Pattern QUOTE = Pattern.compile("[\"“”]");

    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "and", "or", "of", "in", "on", "at", "to", "with",
            "as", "is", "are", "it", "its", "this", "that", "by", "for", "from", "into", "against", "video", "open", "opens", "begins");

    /** {@link #SENTENCE_BREAK}, applied only where no quotation is open. */
    static List<String> splitOutsideQuotes(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '“') quoted = true;
            else if (c == '”') quoted = false;
            else if (c == '"') quoted = !quoted;
            if (quoted || !(c == '.' || c == '!' || c == '?' || c == '"' || c == '”')) continue;
            java.util.regex.Matcher next = SENTENCE_BREAK.matcher(text);
            if (next.find(i + 1) && next.start() == i + 1 && (c != '"' && c != '”' || ".!?".indexOf(text.charAt(i - 1)) >= 0)) {
                out.add(text.substring(start, i + 1).strip());
                start = next.end();
                i = next.end() - 1;
            }
        }
        if (start < text.length() && !text.substring(start).isBlank()) out.add(text.substring(start).strip());
        return out;
    }

    private static Set<String> contentWords(String sentence) {
        Set<String> words = new LinkedHashSet<>();
        for (String w : sentence.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.isBlank() || STOP_WORDS.contains(w)) continue;
            words.add(w.equals("grey") ? "gray" : w.length() > 3 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w);
        }
        return words;
    }

    /** At least 60% of the shorter sentence's content words appear in the other (short ones must match exactly). */
    private static boolean restates(Set<String> a, Set<String> b) {
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        if (smaller.size() < 4) return larger.containsAll(smaller) && smaller.size() == larger.size();
        long shared = smaller.stream().filter(larger::contains).count();
        return shared * 10 >= smaller.size() * 6L;
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
        String movement = camera == null || joined(camera.movementType()) == null ? null
                : joined(camera.movementType(), camera.movementTrajectory(), camera.movementSpeed());
        return movement == null ? null : "Camera: " + movement;
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
            // Planner fields sometimes hold the text "null" rather than no value.
            if (part != null && !part.isBlank() && !"null".equalsIgnoreCase(part.strip())) {
                present.add(part.strip());
            }
        }
        return present.isEmpty() ? null : String.join(", ", present);
    }
}
