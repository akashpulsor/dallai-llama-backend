package com.dalai.llama.preprod.service.assembly;

import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.service.videogen.shotcontext.ShotContext;

import java.util.List;

/**
 * One strategy bean per family of {@link ShotType}s that assemble the same way --
 * {@code ShotContextAssemblyService} selects by type from a {@code Map<ShotType,
 * ShotContextAssemblyStrategy>} built by Spring at startup, so routing a new shot type to an
 * existing or new strategy is a bean-level change, never a branch in an if/switch.
 */
public interface ShotContextAssemblyStrategy {

    List<ShotType> supportedTypes();

    ShotContext assemble(ShotAssemblyContext context);
}
