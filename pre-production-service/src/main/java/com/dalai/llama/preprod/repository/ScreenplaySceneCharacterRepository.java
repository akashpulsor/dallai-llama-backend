package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ScreenplaySceneCharacter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScreenplaySceneCharacterRepository extends JpaRepository<ScreenplaySceneCharacter, UUID> {

    List<ScreenplaySceneCharacter> findByScreenplaySceneId(UUID screenplaySceneId);

    List<ScreenplaySceneCharacter> findByScreenplaySceneIdIn(List<UUID> screenplaySceneIds);
}
