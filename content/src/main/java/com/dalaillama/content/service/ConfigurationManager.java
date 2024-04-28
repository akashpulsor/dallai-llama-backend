package com.dalaillama.content.service;

import com.dalaillama.content.dto.LlmDto;
import com.dalaillama.content.dto.PriceListResponseDto;
import com.dalaillama.content.dto.ToolsDto;
import com.dalaillama.content.entity.Llm;

import java.util.List;

public interface ConfigurationManager {

    ToolsDto addTools(ToolsDto toolsDto);

    List<ToolsDto> getAllTools();

    LlmDto addLlm(LlmDto llmDto);

    List<LlmDto> getAllLlm();

    List<PriceListResponseDto> getPriceList();
}
