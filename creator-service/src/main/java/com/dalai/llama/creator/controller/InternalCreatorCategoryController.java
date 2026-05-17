package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.response.CreatorCategoryKeywordResponse;
import com.dalai.llama.creator.service.CreatorCategoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/internal/creator/categories")
public class InternalCreatorCategoryController {

    private final CreatorCategoryService creatorCategoryService;

    public InternalCreatorCategoryController(CreatorCategoryService creatorCategoryService) {
        this.creatorCategoryService = creatorCategoryService;
    }

    @GetMapping("/keywords")
    public ResponseEntity<List<CreatorCategoryKeywordResponse>> listSchedulerKeywords() {
        return ResponseEntity.ok(creatorCategoryService.listActiveSchedulerKeywords());
    }
}
