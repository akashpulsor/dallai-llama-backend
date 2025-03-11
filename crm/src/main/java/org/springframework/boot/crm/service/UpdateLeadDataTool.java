package org.springframework.boot.crm.service;

import org.springframework.boot.crm.advice.ToolComponent;
import org.springframework.boot.crm.dto.FunctionCallDto;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@ToolComponent
public class UpdateLeadDataTool implements Tool {

    private final ApplicationContext applicationContext;
    private final ApplicationEventPublisher applicationEventPublisher;
    public UpdateLeadDataTool(ApplicationContext  applicationContext,
                              ApplicationEventPublisher applicationEventPublisher) {
        this.applicationContext = applicationContext;
        this.applicationEventPublisher = applicationEventPublisher;
    }
    @Override
    public String getName() {
        return "update_lead_data";
    }

    @Override
    public String getDescription() {
        return "call this function to update lead data";
    }



    public Map<String, Object> getFunctionDefinition() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> streamId = new HashMap<>();
        streamId.put("type", "string");
        streamId.put("description", "The unique identifier for the current voice stream session");

        Map<String, Object> leadId = new HashMap<>();
        leadId.put("type", "string");
        leadId.put("description", "leadId that you have received at the system message");


        Map<String, Object> whatsappNumber = new HashMap<>();
        whatsappNumber.put("type", "string");
        whatsappNumber.put("description", "Whats app number you have asked from lead");

        Map<String, Object> email = new HashMap<>();
        email.put("type", "string");
        email.put("description", "Email you have asked from lead");


        properties.put("streamId", streamId);
        properties.put("leadId", leadId);

        properties.put("whatsappNumber", whatsappNumber);
        properties.put("email", email);

        parameters.put("properties", properties);
        parameters.put("required", Arrays.asList("streamId","leadId"));

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

        return "Lead data is updated in table, and data sent to communication channel provided by client please ask lead to check if they have received the data and respond back";
    }


}
