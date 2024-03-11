package com.dalaillama.content.service;

import com.dalaillama.content.dto.GenerateStructureRequestDto;
import com.dalaillama.content.dto.GenerateStructureResponseDto;

import java.util.List;

public interface ContentService {

    List<GenerateStructureResponseDto> generateStructure(GenerateStructureRequestDto generateStructureRequestDto);
}
