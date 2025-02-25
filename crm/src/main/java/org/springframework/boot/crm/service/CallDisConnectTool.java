package org.springframework.boot.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.crm.advice.ToolComponent;
import org.springframework.boot.crm.dto.FunctionCallDto;
import org.springframework.boot.crm.dto.TwilioCloseEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ToolComponent
public class CallDisConnectTool implements Tool {

    private final ApplicationContext applicationContext;
    private final ApplicationEventPublisher applicationEventPublisher;
    public CallDisConnectTool(ApplicationContext  applicationContext,
                              ApplicationEventPublisher applicationEventPublisher) {
        this.applicationContext = applicationContext;
        this.applicationEventPublisher = applicationEventPublisher;
    }
    @Override
    public String getName() {
        return "disconnect_call";
    }

    @Override
    public String getDescription() {
        return "To disconnect call use this function";
    }



    public Map<String, Object> getFunctionDefinition() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> streamId = new HashMap<>();
        streamId.put("type", "string");
        streamId.put("description", "The unique identifier for the current voice stream session");
        properties.put("streamId", streamId);
        parameters.put("properties", properties);
        parameters.put("required", Arrays.asList("streamId"));
        return parameters;

    }

    @Override
    public String functionImplementation(Object... args) throws IOException {
        //TODO add instance of check
        FunctionCallDto functionCallDto = (FunctionCallDto) args[0];
        if((functionCallDto.getArguments() != null) && (functionCallDto.getParsedArguments() != null) &&
                !functionCallDto.getParsedArguments().containsKey("streamId")){
            throw  new IllegalStateException("Received improper function call");
        }

        return "Call Disconnected, please close open ai session";
    }


}
