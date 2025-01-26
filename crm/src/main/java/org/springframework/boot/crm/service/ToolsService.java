package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.OpenAiRequestDto;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class ToolsService {

    private final LeadManager leadManager;

    private final CampaignManager   campaignManager;

    public ToolsService(LeadManager leadManager,CampaignManager campaignManager) {
        this.leadManager = leadManager;
        this.campaignManager = campaignManager;
    }



    public List<Map<String, Object>> getTools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // Add billing information function
        //Map<String, Object> billingFunction = new HashMap<>();
        //billingFunction.put("type", "function");
        //billingFunction.put("function", createBillingFunctionSpec());
        //tools.add(billingFunction);

        // Add get lead details function
        Map<String, Object> leadFunction = new HashMap<>();
        leadFunction.put("type", "function");
        leadFunction.put("function", createLeadFunctionSpec());
        //tools.add(leadFunction);
        return tools;
    }

    private Map<String, Object> createLeadFunctionSpec() {
        Map<String, Object> functionSpec = new HashMap<>();
        functionSpec.put("name", "updateWhatsApp");
        functionSpec.put("description", "Get lead information including contact details");

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("required", Arrays.asList("lead_id", "whatsapp_number"));

        Map<String, Object> properties = new HashMap<>();

        // Lead ID property
        Map<String, Object> leadIdProp = new HashMap<>();
        leadIdProp.put("type", "integer");
        leadIdProp.put("description", "The unique identifier for the lead");
        properties.put("lead_id", leadIdProp);

        // WhatsApp number property
        Map<String, Object> whatsappProp = new HashMap<>();
        whatsappProp.put("type", "string");
        whatsappProp.put("description", "WhatsApp contact number of the lead");
        properties.put("whatsapp_number", whatsappProp);

        parameters.put("properties", properties);
        functionSpec.put("parameters", parameters);

        return functionSpec;
    }

    private Map<String, Object> createBillingFunctionSpec() {
        Map<String, Object> functionSpec = new HashMap<>();
        functionSpec.put("name", "sendBillingInformation");
        functionSpec.put("description", "Send billing information for the conversation including tokens and charges");

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("required", Arrays.asList(
                "callLogId",
                "input_token",
                "output_token",
                "totalToken",
                "totalCharges"
        ));

        Map<String, Object> properties = new HashMap<>();

        // CallLogId property
        Map<String, Object> callLogIdProp = new HashMap<>();
        callLogIdProp.put("type", "integer");
        callLogIdProp.put("description", "The ID of the call log");
        properties.put("callLogId", callLogIdProp);

        // Input token property
        Map<String, Object> inputTokenProp = new HashMap<>();
        inputTokenProp.put("type", "integer");
        inputTokenProp.put("description", "Number of input tokens used");
        properties.put("input_token", inputTokenProp);

        // Output token property
        Map<String, Object> outputTokenProp = new HashMap<>();
        outputTokenProp.put("type", "integer");
        outputTokenProp.put("description", "Number of output tokens used");
        properties.put("output_token", outputTokenProp);

        // Total token property
        Map<String, Object> totalTokenProp = new HashMap<>();
        totalTokenProp.put("type", "integer");
        totalTokenProp.put("description", "Total number of tokens used");
        properties.put("totalToken", totalTokenProp);

        // Total charges property
        Map<String, Object> totalChargesProp = new HashMap<>();
        totalChargesProp.put("type", "number");
        totalChargesProp.put("description", "Total charges for the conversation");
        properties.put("totalCharges", totalChargesProp);

        parameters.put("properties", properties);
        functionSpec.put("parameters", parameters);

        return functionSpec;
    }




}
