package com.dalaillama.content.service;

import com.dalaillama.content.dto.GenerateArticleRequestDto;
import com.dalaillama.content.dto.GenerateStructureRequestDto;
import com.dalaillama.content.dto.GenerateStructureResponseDto;
import com.dalaillama.content.dto.GeneratedArticleResponseDto;

import java.util.List;

public interface ContentService {

    GenerateStructureResponseDto generateStructure(GenerateStructureRequestDto generateStructureRequestDto);

    GeneratedArticleResponseDto generateArticle(GenerateArticleRequestDto generateArticleRequestDto);
}
