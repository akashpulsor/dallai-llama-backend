package com.dalai.llama.preprod.service.assembly;

import com.dalai.llama.preprod.domain.EmotionalArcPosition;
import com.dalai.llama.preprod.domain.entity.CastAssignment;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.domain.entity.ContinuityLock;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.domain.entity.ProjectConfig;
import com.dalai.llama.preprod.domain.entity.ScreenplayScene;
import com.dalai.llama.preprod.domain.entity.Shot;

import java.util.List;

/**
 * Everything a {@link ShotContextAssemblyStrategy} needs to build one {@code ShotContext} --
 * assembled once by {@code ShotContextAssemblyService} and handed to whichever strategy the
 * shot's {@link com.dalai.llama.preprod.domain.ShotType} routes to.
 * {@code castAssignment}/{@code castProfile} are null when the shot has no primary character or
 * that character has no cast assignment yet. {@code continuityLocks} is the project's whole
 * {@code ContinuityBible} (never empty-by-default anymore, see {@code
 * ShotContextCommonFields#continuityAnchors}); {@code previousShot} is null for the first shot in
 * the project's shot-number ordering.
 */
public record ShotAssemblyContext(
        Shot shot,
        Project project,
        ProjectConfig projectConfig,
        ScreenplayScene scene,
        EmotionalArcPosition arcPosition,
        CastAssignment castAssignment,
        CastProfile castProfile,
        List<ContinuityLock> continuityLocks,
        Shot previousShot
) {
}
