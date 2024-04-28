package com.dalaillama.content.controller;

import com.dalaillama.content.dto.*;
import com.dalaillama.content.service.ConfigurationManager;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/config")
public class ConfigurationController {

    private final ConfigurationManager configurationManager;
    public ConfigurationController(ConfigurationManager configurationManager) {
        this.configurationManager = configurationManager;
    }


    @PostMapping("/add-tools")
    public ToolsDto addTools(@RequestBody ToolsDto toolsDto) {
        return this.configurationManager.addTools(toolsDto);
    }

    @GetMapping("/get-tools")
    public List<ToolsDto> getTools() {
        return this.configurationManager.getAllTools();
    }

    @PostMapping("/add-llm")
    public LlmDto addLlm(@RequestBody LlmDto llmDto) {
        return this.configurationManager.addLlm(llmDto);
    }

    @GetMapping("/get-llm")
    public List<LlmDto> getLlm() {
        return this.configurationManager.getAllLlm();
    }


    @GetMapping("/price-list")
    public List<PriceListResponseDto> getPriceList() {
        return this.configurationManager.getPriceList();
    }
}
