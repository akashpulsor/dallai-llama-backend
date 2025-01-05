package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.OpenAiRequestDto;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ToolsService {

    private final LeadManager leadManager;

    private final CampaignManager   campaignManager;

    public ToolsService(LeadManager leadManager,CampaignManager campaignManager) {
        this.leadManager = leadManager;
        this.campaignManager = campaignManager;
    }



    public List<String> getTools() {
        List<String> tools = new ArrayList<>();

        return tools;
    }




}
