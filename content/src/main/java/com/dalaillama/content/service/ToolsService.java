package com.dalaillama.content.service;

import com.dalaillama.content.dto.ToolsDto;
import com.dalaillama.content.entity.Tools;

import java.util.List;

public interface ToolsService {


    Tools addTools(Tools tools);

    List<Tools> getAllTools();
}
