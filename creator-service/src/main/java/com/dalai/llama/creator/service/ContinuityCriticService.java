package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.dto.shotplan.CharacterRenderSpecView;
import com.dalai.llama.creator.dto.shotplan.ShotPlanTagMapper;
import com.dalai.llama.creator.dto.shotplan.StoryboardTagView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic-only continuity check across a script's approved shot sequence - no AI call, same
 * "checkable facts don't need a judgment call" reasoning as ShotPlanCriticService's variety/coverage
 * checks and ShortContinuityCriticService's edit-decision-list audits. Two things it actually
 * verifies: (1) the same character is assigned the same cast actor everywhere in the script - a
 * mismatch here is an unambiguous bug, never a creative choice; (2) a character's wardrobe doesn't
 * silently change between two shots that share the same sceneLocation, which would read as a
 * continuity error on screen. Set/background drift between shots in the same location is flagged
 * as a soft warning only (text-similarity heuristics are too noisy to gate a regeneration on).
 *
 * Cast-face-drift (does the generated IMAGE match the reference photo) is handled separately by
 * ProductFrameCriticService.castConsistencyScore, since that check needs to see the rendered
 * pixels - this service only reasons over the structured plan data.
 */
@Service
public class ContinuityCriticService {

    private final ObjectMapper objectMapper;

    public ContinuityCriticService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record ContinuityCriticResult(
            String status,
            double confidence,
            int continuityScore,
            List<String> issues,
            List<Integer> failedShotNumbers,
            String summary
    ) {
        public boolean isFail() {
            return "FAIL".equals(status);
        }
    }

    public ContinuityCriticResult critique(List<CreatorScriptShotPlan> plans) {
        List<CreatorScriptShotPlan> sorted = plans == null
                ? List.of()
                : plans.stream().sorted((a, b) -> Integer.compare(shotNumber(a), shotNumber(b))).toList();
        if (sorted.size() < 2) {
            return new ContinuityCriticResult("PASS", 1.0, 100, List.of(), List.of(), "Not enough shots to check continuity.");
        }

        List<String> hardIssues = new ArrayList<>();
        List<String> softIssues = new ArrayList<>();
        Set<Integer> failedShots = new LinkedHashSet<>();

        checkActorAssignmentConsistency(sorted, hardIssues, failedShots);
        checkWardrobeContinuity(sorted, hardIssues, failedShots);
        checkSetDesignDrift(sorted, softIssues);

        List<String> allIssues = new ArrayList<>(hardIssues);
        allIssues.addAll(softIssues);
        int score = Math.max(0, 100 - hardIssues.size() * 20 - softIssues.size() * 5);
        String status = !hardIssues.isEmpty() ? "FAIL" : !softIssues.isEmpty() ? "WARN" : "PASS";
        String summary = allIssues.isEmpty()
                ? "No continuity issues found across the shot sequence."
                : allIssues.size() + " continuity issue(s) found: " + String.join(" ", allIssues.subList(0, Math.min(2, allIssues.size())));
        return new ContinuityCriticResult(status, 0.9, score, allIssues, List.copyOf(failedShots), summary);
    }

    private void checkActorAssignmentConsistency(List<CreatorScriptShotPlan> sorted, List<String> issues, Set<Integer> failedShots) {
        Map<String, String> actorByCharacter = new LinkedHashMap<>();
        Map<String, Integer> firstShotByCharacter = new LinkedHashMap<>();
        for (CreatorScriptShotPlan plan : sorted) {
            StoryboardTagView tag = storyboardTag(plan);
            for (CharacterRenderSpecView character : allCharacters(tag)) {
                String name = normalizedName(character.storyCharacterName());
                String actor = character.assignedActorName();
                if (name.isEmpty() || actor.isBlank()) {
                    continue;
                }
                String previousActor = actorByCharacter.get(name);
                if (previousActor == null) {
                    actorByCharacter.put(name, actor);
                    firstShotByCharacter.put(name, shotNumber(plan));
                } else if (!previousActor.equalsIgnoreCase(actor)) {
                    issues.add("Character \"" + character.storyCharacterName() + "\" is cast as \"" + previousActor
                            + "\" in shot " + firstShotByCharacter.get(name) + " but \"" + actor + "\" in shot " + shotNumber(plan) + ".");
                    failedShots.add(shotNumber(plan));
                }
            }
        }
    }

    private void checkWardrobeContinuity(List<CreatorScriptShotPlan> sorted, List<String> issues, Set<Integer> failedShots) {
        for (int i = 1; i < sorted.size(); i++) {
            CreatorScriptShotPlan previousPlan = sorted.get(i - 1);
            CreatorScriptShotPlan currentPlan = sorted.get(i);
            StoryboardTagView previousTag = storyboardTag(previousPlan);
            StoryboardTagView currentTag = storyboardTag(currentPlan);
            String previousLocation = previousTag.sceneLocation();
            String currentLocation = currentTag.sceneLocation();
            if (previousLocation == null || currentLocation == null
                    || previousLocation.isBlank() || currentLocation.isBlank()
                    || !previousLocation.equalsIgnoreCase(currentLocation)) {
                continue;
            }
            Map<String, CharacterRenderSpecView> previousByName = byName(allCharacters(previousTag));
            for (CharacterRenderSpecView current : allCharacters(currentTag)) {
                String name = normalizedName(current.storyCharacterName());
                CharacterRenderSpecView previous = previousByName.get(name);
                if (previous == null || current.wardrobeThisShot().isBlank() || previous.wardrobeThisShot().isBlank()) {
                    continue;
                }
                if (!wordOverlap(previous.wardrobeThisShot(), current.wardrobeThisShot())) {
                    issues.add("\"" + current.storyCharacterName() + "\" wears a different outfit in shot " + shotNumber(currentPlan)
                            + " than shot " + shotNumber(previousPlan) + " despite both being set at \"" + currentLocation + "\".");
                    failedShots.add(shotNumber(currentPlan));
                }
            }
        }
    }

    private void checkSetDesignDrift(List<CreatorScriptShotPlan> sorted, List<String> softIssues) {
        for (int i = 1; i < sorted.size(); i++) {
            StoryboardTagView previousTag = storyboardTag(sorted.get(i - 1));
            StoryboardTagView currentTag = storyboardTag(sorted.get(i));
            String previousLocation = previousTag.sceneLocation();
            String currentLocation = currentTag.sceneLocation();
            if (previousLocation == null || currentLocation == null
                    || previousLocation.isBlank() || currentLocation.isBlank()
                    || !previousLocation.equalsIgnoreCase(currentLocation)) {
                continue;
            }
            if (!previousTag.setDesign().isBlank() && !currentTag.setDesign().isBlank()
                    && !wordOverlap(previousTag.setDesign(), currentTag.setDesign())) {
                softIssues.add("Set design description drifts between shot " + shotNumber(sorted.get(i - 1)) + " and shot "
                        + shotNumber(sorted.get(i)) + " despite both being set at \"" + currentLocation + "\" - verify this is intentional.");
            }
        }
    }

    private List<CharacterRenderSpecView> allCharacters(StoryboardTagView tag) {
        List<CharacterRenderSpecView> all = new ArrayList<>(tag.primaryCharacters());
        all.addAll(tag.sideCharacters());
        return all;
    }

    private Map<String, CharacterRenderSpecView> byName(List<CharacterRenderSpecView> characters) {
        Map<String, CharacterRenderSpecView> byName = new LinkedHashMap<>();
        for (CharacterRenderSpecView character : characters) {
            String name = normalizedName(character.storyCharacterName());
            if (!name.isEmpty()) {
                byName.putIfAbsent(name, character);
            }
        }
        return byName;
    }

    /** True when the two descriptions share enough vocabulary to plausibly describe the same thing - a cheap, deliberately lenient heuristic to avoid false positives from phrasing differences. */
    private boolean wordOverlap(String left, String right) {
        Set<String> leftWords = significantWords(left);
        Set<String> rightWords = significantWords(right);
        if (leftWords.isEmpty() || rightWords.isEmpty()) {
            return true;
        }
        long shared = leftWords.stream().filter(rightWords::contains).count();
        int smaller = Math.min(leftWords.size(), rightWords.size());
        return smaller == 0 || (double) shared / smaller >= 0.3;
    }

    private Set<String> significantWords(String value) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() > 2) {
                words.add(word);
            }
        }
        return words;
    }

    private String normalizedName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private StoryboardTagView storyboardTag(CreatorScriptShotPlan plan) {
        return ShotPlanTagMapper.storyboardTag(plan.getStoryboardTag(), objectMapper);
    }

    private int shotNumber(CreatorScriptShotPlan plan) {
        return plan.getShotNumber() == null ? 0 : plan.getShotNumber();
    }
}
