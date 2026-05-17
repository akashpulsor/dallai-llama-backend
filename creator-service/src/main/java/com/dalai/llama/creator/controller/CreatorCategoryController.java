package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.response.CreatorCategoryResponse;
import com.dalai.llama.creator.service.CreatorCategoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/creator/categories")
public class CreatorCategoryController {

    private final CreatorCategoryService creatorCategoryService;

    public CreatorCategoryController(CreatorCategoryService creatorCategoryService) {
        this.creatorCategoryService = creatorCategoryService;
    }

    @GetMapping
    public ResponseEntity<List<CreatorCategoryResponse>> listCategories() {
        return ResponseEntity.ok(creatorCategoryService.listVisibleCategories());
    }
}
