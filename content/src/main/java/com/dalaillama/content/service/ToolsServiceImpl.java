package com.dalaillama.content.service;

import com.dalaillama.content.dto.ToolsDto;
import com.dalaillama.content.entity.Tools;
import com.dalaillama.content.repository.ToolsRepository;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class ToolsServiceImpl implements  ToolsService {

    private final ToolsRepository toolsRepository;

    public ToolsServiceImpl(ToolsRepository toolsRepository) {
        this.toolsRepository = toolsRepository;
    }
    @Override
    public Tools addTools(Tools tools) {
        this.toolsRepository.save(tools);
        return tools;
    }

    @Override
    public List<Tools> getAllTools() {
        return this.toolsRepository.findAll();
    }



}
