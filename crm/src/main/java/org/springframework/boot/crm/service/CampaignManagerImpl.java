package org.springframework.boot.crm.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.crm.dto.CampaignDataRequestDto;
import org.springframework.boot.crm.dto.CampaignDataResponseDto;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class CampaignManagerImpl implements  CampaignManager {
    private CampaignService campaignService;

    public CampaignManagerImpl(CampaignService campaignService) {
        this.campaignService = campaignService;
    }
        @Override
    public CampaignDataResponseDto add(CampaignDataRequestDto campaignDataRequestDto) {
        CampaignData campaignData = this.campaignService.save(dtoToModel(campaignDataRequestDto));
        return modelToDto(campaignData);
    }

    @Override
    public CampaignDataResponseDto get(int campaignId, int businessId) {
        CampaignData campaignData = this.campaignService.findByCampaignIdAndBusinessId(campaignId, businessId);
        return modelToDto(campaignData);
    }

    @Override
    public List<CampaignDataResponseDto> getByBusinessId(int  businessId) {
        return this.campaignService.getAllByBusinessId(businessId).stream().
                map(this::modelToDto).collect(Collectors.toList());
    }



    private CampaignData dtoToModel(CampaignDataRequestDto campaignDataRequestDto){
        CampaignData campaignData = new CampaignData();
        campaignData.setBusinessId(campaignDataRequestDto.getBusinessId());
        if(!StringUtils.isBlank(campaignDataRequestDto.getCampaignAim())) campaignData.setCampaignAim(campaignDataRequestDto.getCampaignAim());
        if(!StringUtils.isBlank(campaignDataRequestDto.getCampaignDesc())) campaignData.setCampaignDesc(campaignDataRequestDto.getCampaignDesc());
        if(!StringUtils.isBlank(campaignDataRequestDto.getCampaignImgUrl())) campaignData.setCampaignImgUrl(campaignDataRequestDto.getCampaignImgUrl());
        if(!StringUtils.isBlank(campaignDataRequestDto.getCampaignName())) campaignData.setCampaignName(campaignDataRequestDto.getCampaignName());
        if(!StringUtils.isBlank(campaignDataRequestDto.getCampaignPrompt())) campaignData.setCampaignPrompt(campaignDataRequestDto.getCampaignPrompt());
        if(!StringUtils.isBlank(campaignDataRequestDto.getConversationGuideLines())) campaignData.setConversationGuideLines(campaignDataRequestDto.getConversationGuideLines());
        if(!StringUtils.isBlank(campaignDataRequestDto.getFirstMessage())) campaignData.setFirstMessage(campaignDataRequestDto.getFirstMessage());
        if(!StringUtils.isBlank(campaignDataRequestDto.getHandlingFaq())) campaignData.setHandlingFaq(campaignDataRequestDto.getHandlingFaq());
        if(!StringUtils.isBlank(campaignDataRequestDto.getPlacingOrder()))campaignData.setPlacingOrder(campaignDataRequestDto.getPlacingOrder());
        return campaignData;
    }

    private CampaignDataResponseDto modelToDto(CampaignData campaignData){
        CampaignDataResponseDto campaignDataResponseDto = new CampaignDataResponseDto();
        campaignDataResponseDto.setBusinessId(campaignData.getBusinessId());
        campaignDataResponseDto.setCampaignId(campaignData.getCampaignId());
        if(!StringUtils.isBlank(campaignData.getCampaignAim())) campaignDataResponseDto.setCampaignAim(campaignData.getCampaignAim());
        if(!StringUtils.isBlank(campaignData.getCampaignDesc())) campaignDataResponseDto.setCampaignDesc(campaignData.getCampaignDesc());
        if(!StringUtils.isBlank(campaignData.getCampaignImgUrl())) campaignDataResponseDto.setCampaignImgUrl(campaignData.getCampaignImgUrl());
        if(!StringUtils.isBlank(campaignData.getCampaignName())) campaignDataResponseDto.setCampaignName(campaignData.getCampaignName());
        if(!StringUtils.isBlank(campaignData.getCampaignPrompt())) campaignDataResponseDto.setCampaignPrompt(campaignData.getCampaignPrompt());
        if(!StringUtils.isBlank(campaignData.getConversationGuideLines())) campaignDataResponseDto.setConversationGuideLines(campaignData.getConversationGuideLines());
        if(!StringUtils.isBlank(campaignData.getFirstMessage())) campaignDataResponseDto.setFirstMessage(campaignData.getFirstMessage());
        if(!StringUtils.isBlank(campaignData.getHandlingFaq())) campaignDataResponseDto.setHandlingFaq(campaignData.getHandlingFaq());
        if(!StringUtils.isBlank(campaignData.getPlacingOrder())) campaignDataResponseDto.setPlacingOrder(campaignData.getPlacingOrder());
        return campaignDataResponseDto;
    }
}
