package com.dalaillama.content.service;

import com.dalaillama.content.dto.LlmDto;
import com.dalaillama.content.dto.PriceListRequestDto;
import com.dalaillama.content.dto.PriceListResponseDto;
import com.dalaillama.content.dto.ToolsDto;
import com.dalaillama.content.entity.Llm;
import com.dalaillama.content.entity.Tools;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;


@Component
public class ConfigurationManagerImpl implements ConfigurationManager {

    private final ToolsService toolsService;
    private final LlmService llmService;

    private final PriceService priceService;
    public ConfigurationManagerImpl(ToolsService toolsService,LlmService llmService,PriceService priceService){
        this.toolsService = toolsService;
        this.llmService = llmService;
        this.priceService = priceService;
    }
    @Override
    public ToolsDto addTools(ToolsDto toolsDto) {
        Tools tools = mapToolsDtoToModel(toolsDto);
        this.toolsService.addTools(tools);
        return mapToolsModelToDto(tools);
    }

    @Override
    public List<ToolsDto> getAllTools() {
        return this.toolsService.getAllTools().stream().map(this::mapToolsModelToDto).toList();
    }

    @Override
    public LlmDto addLlm(LlmDto llmDto) {
        Llm llm =mapLlmDtoToModel(llmDto);
        this.llmService.addLlm(llm);
        return mapLlmModelToDto(llm);
    }

    @Override
    public List<LlmDto> getAllLlm() {
        return this.llmService.getAllLlm().stream().map(this::mapLlmModelToDto).toList();
    }


    @Override
    public List<PriceListResponseDto> getPriceList() {
        return this.priceService.getAllPrice();
    }

    @Override
    public PriceListResponseDto addPriceList(PriceListRequestDto priceListRequestDto)
    {
        return this.priceService.addPrice(priceListRequestDto);
    }



    private Tools mapToolsDtoToModel(ToolsDto toolsDto) {
        Tools tools = new Tools();
        tools.setName(toolsDto.getName());
        tools.setTitle(toolsDto.getTitle());
        tools.setCreatorId(toolsDto.getCreatorId());
        tools.setCreatorName(toolsDto.getCreatorName());
        tools.setActive(toolsDto.isActive());
        return tools;
    }

    private ToolsDto mapToolsModelToDto(Tools tools) {
        ToolsDto toolsDto = new ToolsDto();
        toolsDto.setToolId(tools.getToolId());
        toolsDto.setCreatorId(tools.getCreatorId());
        toolsDto.setName(tools.getName());
        toolsDto.setTitle(tools.getTitle());
        toolsDto.setCreatorId(tools.getCreatorId());
        toolsDto.setCreatorName(tools.getCreatorName());
        toolsDto.setActive(tools.isActive());
        return toolsDto;
    }

    private Llm mapLlmDtoToModel(LlmDto llmDto) {
        Llm llm = new Llm();
        llm.setLabel(llmDto.getLabel());
        llm.setValue(llmDto.getValue());
        llm.setCreatorId(llmDto.getCreatorId());
        llm.setActive(llmDto.isActive());
        llm.setLlmToken(llmDto.getLlmToken());
        return llm;
    }

    private LlmDto mapLlmModelToDto(Llm llm) {
        LlmDto llmDto = new LlmDto();
        llmDto.setLlmId(llm.getLlmId());
        llmDto.setLabel(llm.getLabel());
        llmDto.setValue(llm.getValue());
        llmDto.setCreatorId(llm.getCreatorId());
        llmDto.setActive(llm.isActive());
        llmDto.setLlmToken(llm.getLlmToken());
        return llmDto;
    }
}
