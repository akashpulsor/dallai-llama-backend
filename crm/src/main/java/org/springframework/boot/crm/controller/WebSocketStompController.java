package org.springframework.boot.crm.controller;

import org.springframework.boot.crm.dto.ChargesSummaryDto;
import org.springframework.boot.crm.dto.DashBoardDataDto;
import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.ChargesData;
import org.springframework.boot.crm.service.ChargesDataService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Controller
public class WebSocketStompController {

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketStompController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void sendDashboardUpdate(int businessId, DashBoardDataDto update) {
        messagingTemplate.convertAndSend("/topic/business/" + businessId + "/dashboard", update);
    }



    //create controller to send  campaign Charges
    public void sendCampaignChargesUpdate(int businessId, int campaignId, ChargesSummaryDto chargesSummary) {
        messagingTemplate.convertAndSend("/topic/business/" + businessId + "/campaign/"+campaignId+"/charges", chargesSummary);
    }

    //create controller to send  campaign run Charges
    public void sendCampaignRunChargesUpdate(int businessId, int campaignId, int campaignRunId, ChargesSummaryDto chargesSummary) {
        messagingTemplate.convertAndSend("/topic/business/" + businessId + "/campaign/"+campaignId+"/run/"+campaignRunId+"/charges", chargesSummary);
    }

    //create controller to send  call Charges
    public void sendCallChargesUpdate(int businessId, int campaignId, int campaignRunId, ChargesData chargesData) {
        messagingTemplate.convertAndSend("/topic/business/" + businessId + "/campaign/"+campaignId+"/run/"+campaignRunId+"/calls/charges", chargesData);
    }

    //create controller to send  call conversation
    public void sendCallConversationUpdate(int businessId, int campaignId, int campaignRunId, CallLog callLog) {
        messagingTemplate.convertAndSend("/topic/business/" + businessId + "/campaign/"+campaignId+"/run/"+campaignRunId+"/calls/conversation", callLog);
    }

    //create controller to send  that call is in busy wait state
    public void sendCallBusyWaitUpdate(int businessId, int campaignId, int campaignRunId, boolean wait) {
        messagingTemplate.convertAndSend("/topic/business/" + businessId + "/campaign/"+campaignId+"/run/"+campaignRunId+"/calls/sleep", wait);
    }

    public void sendCallUpdate(int businessId,int campaignId, int campaignRunId, CallLog callLog) {
        messagingTemplate.convertAndSend("/topic/business/" + businessId + "/campaign/"+campaignId+"/run/"+campaignRunId+"/calls", callLog);
    }

    public void sendChargesUpdate(int businessId, ChargesSummaryDto chargesSummary) {
        messagingTemplate.convertAndSend("/topic/business/" + businessId + "/charges", chargesSummary);
    }


}