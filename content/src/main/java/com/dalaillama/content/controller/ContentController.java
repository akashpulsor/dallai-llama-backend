package com.dalaillama.content.controller;

import com.dalaillama.content.dto.GenerateArticleRequestDto;
import com.dalaillama.content.dto.GenerateStructureRequestDto;
import com.dalaillama.content.dto.GenerateStructureResponseDto;
import com.dalaillama.content.dto.GeneratedArticleResponseDto;
import com.dalaillama.content.service.ContentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
//OPENAI_API_KEY="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"
//SERPAPI_API_KEY="262b581dac1cc55105c69261270f183127d0e361e3bdd6d57579173b94a80204"
@RestController
public class ContentController {

    private final ContentService contentService;
    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }
    @PostMapping("/generateStructure")
    GenerateStructureResponseDto generateStructure(@RequestBody GenerateStructureRequestDto generateStructureRequestDto) {
        return this.contentService.generateStructure(generateStructureRequestDto);
    }

    @PostMapping("/generateArticle")
    GeneratedArticleResponseDto generateArticle(@RequestBody GenerateArticleRequestDto generateArticleRequestDto) {
        return this.contentService.generateArticle(generateArticleRequestDto);
    }
}
