package org.springframework.boot.crm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.advice.ToolComponent;
import org.springframework.boot.crm.dto.OpenAiRequestDto;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.boot.crm.exceptions.ToolExecutionException;
import org.springframework.boot.crm.exceptions.ToolNotFoundException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ToolsService {



    private final Map<String, Tool> tools;
    private final ApplicationContext applicationContext;
    public ToolsService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.tools = discoveryTools();
        log.info("Discovered {} tools: {}", tools.size(),
                tools.keySet().stream().collect(Collectors.joining(", ")));
    }

    private Map<String, Tool> discoveryTools() {
        // Find all beans of type Tool in the application context
        Map<String, Object> toolBeans = applicationContext.getBeansWithAnnotation(ToolComponent.class);

        return toolBeans.values().stream()
                .filter(bean -> bean instanceof Tool)
                .map(bean -> (Tool) bean)
                .collect(Collectors.toMap(
                        Tool::getName,
                        Function.identity(),
                        (existing, replacement) -> {
                            log.warn("Duplicate tool name found: {}. Keeping existing implementation.",
                                    existing.getName());
                            return existing;
                        }
                ));
    }


    public List<Tool> getAllTools() {
        return  tools.values().stream().toList(); // Return a read-only list
    }

    public List<Map<String, Object>> getTools() {
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (Tool tool : tools.values()) {
            Map<String, Object> toolDefinition = new HashMap<>();
            toolDefinition.put("type", "function");
            toolDefinition.put("name", tool.getName());
            toolDefinition.put("description", tool.getDescription());
            toolDefinition.put("parameters", tool.getFunctionDefinition());
            toolList.add(toolDefinition);
        }
        return toolList;
    }

    public Map<String, Object> createToolResponse(String toolCallId, Object result) throws JsonProcessingException {
        Map<String, Object> response = new HashMap<>();
        response.put("role", "tool");
        response.put("content", new ObjectMapper().writeValueAsString(result));
        response.put("tool_call_id", toolCallId);
        return response;
    }

    public String executeTool(String toolName, Object... args) {
        Tool tool = getToolByName(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }

        try {
            return tool.functionImplementation(args);
        } catch (Exception e) {
            log.error("Error executing tool {}: {}", toolName, e.getMessage());
            throw new ToolExecutionException("Failed to execute tool: " + toolName, e);
        }
    }

    public Tool getToolByName(String functionName) {
        return tools.get(functionName);
    }



}
