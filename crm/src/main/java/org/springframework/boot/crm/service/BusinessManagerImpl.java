package org.springframework.boot.crm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BusinessManagerImpl implements BusinessManager {
    private final BusinessService businessService;


    private final MasterDataService masterDataService;

    private final PhoneService phoneService;

    private final DalaiLLamaLeadDataService dalaiLLamaLeadDataService;

    private final BusinessIndiaService businessIndiaService;

    private final CampaignManager campaignManager;

    private final AgentManager agentManager;

    private final LeadManager leadManager;

    private final MetaManager metaManager;

    private final CallManager callManager;
    private final ApplicationEventPublisher applicationEventPublisher;

    public BusinessManagerImpl( BusinessService businessService,
                                MasterDataService masterDataService,
                                PhoneService phoneService,
                                BusinessIndiaService businessIndiaService,
                                CampaignManager campaignManager,
                                AgentManager agentManager,
                                LeadManager leadManager,
                                MetaManager metaManager,CallManager callManager,ApplicationEventPublisher applicationEventPublisher,
                                DalaiLLamaLeadDataService dalaiLLamaLeadDataService){
        this.businessService = businessService;
        this.masterDataService = masterDataService;
        this.phoneService = phoneService;
        this.businessIndiaService = businessIndiaService;
        this.campaignManager = campaignManager;
        this.agentManager = agentManager;
        this.leadManager = leadManager;
        this.metaManager = metaManager;
        this.callManager = callManager;
        this.dalaiLLamaLeadDataService = dalaiLLamaLeadDataService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public CampaignManager getCampaignManager() {
        return this.campaignManager;
    }

    @Override
    public AgentManager getAgentManager() {
        return this.agentManager;
    }

    @Override
    public LeadManager getLeadManager() {
        return this.leadManager;
    }

    @Override
    public LlmData addLlmData(LlmData llmData) {
        return this.metaManager.addLlmData(llmData);
    }

    @Override
    public TwilioData addTwilioData(TwilioData twilioData) {
        return this.metaManager.addTwilioData(twilioData);
    }

    @Override
    public List<LlmData> getAllLlmData(int businessId) {
        return this.metaManager.getLlmData(businessId);
    }

    @Override
    public List<TwilioData> getAllTwilioData(int businessId) {
        return this.metaManager.getTwilioData(businessId);
    }

    @Override
    public MetaManager getMetaManager() {
        return this.metaManager;
    }

    @Override
    public DalaiLlamaLeads addDalaiLLamaLeads(DalaiLlamaLeadsDto dalaiLlamaLeads) {
        log.info("Dalai llama leads - {}", dalaiLlamaLeads);
        return this.dalaiLLamaLeadDataService.save(leadDtoToModel(dalaiLlamaLeads));
    }




    @Override
    public BusinessData getBusinessData(int businessId) {
        return this.businessService.getBusinessDataById(businessId);
    }

    @Override
    public BusinessData addBusiness(BusinessData businessData) {
        return this.businessService.addBusiness(businessData);
    }

    @Override
    public CampaignDataResponseDto add(CampaignDataRequestDto campaignDataRequestDto) {
        this.getBusinessData(campaignDataRequestDto.getBusinessId());
        return getCampaignManager().add(campaignDataRequestDto);
    }

    @Override
    public CampaignDataResponseDto get(int campaignId, int businessId) {
        this.getBusinessData(businessId);
        return getCampaignManager().get(campaignId, businessId);
    }

    @Override
    public List<CampaignDataResponseDto> getByBusinessId(int  businessId) {
        this.getBusinessData(businessId);
        return getCampaignManager().getByBusinessId(businessId);
    }


    @EventListener
    public  void handleTwilioEvent(TwilioStartEventDto twilioStartEventDto) throws IOException, InterruptedException {
        log.info("twilio start event Received - {}", twilioStartEventDto);
        int businessId = twilioStartEventDto.getTwilioStartMediaMessage().getStart().getCustomParameters().getBusinessId();
        int campaignRunId = twilioStartEventDto.getTwilioStartMediaMessage().getStart().getCustomParameters().getCampaignRunId();
        int leadId = twilioStartEventDto.getTwilioStartMediaMessage().getStart().getCustomParameters().getLeadId();
        CampaignRunData campaignRunData = this.campaignManager.getCampaignRunData( campaignRunId,businessId);
        LlmData llmData = this.metaManager.getLlmData(businessId,campaignRunData.getLlmId());
        TwilioData twilioData = this.metaManager.getTwilioData(businessId,campaignRunData.getPhoneId());
        AgentData agentData = this.agentManager.findByBusinessIdAndAgentId(businessId, campaignRunData.getAgentId());
        CampaignData campaignData = this.campaignManager.getCampaignData(campaignRunData.getCampaignId(), campaignRunData.getBusinessId());
        LeadData leadData = this.leadManager.getLeadData(businessId,leadId);
        BusinessData businessData = this.getBusinessData(businessId);
        String systemMessage =this.callManager.createSystemMessage(agentData,campaignData,businessData, campaignRunData);
        this.callManager.handleTwilioEvent(twilioStartEventDto,systemMessage, llmData, leadData, campaignData);
        StartCallEvent startCallEvent = new StartCallEvent(this,twilioStartEventDto);
        this.applicationEventPublisher.publishEvent(startCallEvent);
    }

    @EventListener
    public  void handleTwilioEvent(TwilioMediaEventDto twilioMediaEventDto) throws JsonProcessingException {
        //log.info("twilio event Received - {}", twilioMediaEventDto);
        MediaEventDto twilioMediaMessage = twilioMediaEventDto.getMediaEventDto();
        Map<String,Object> map = new HashMap<>();
        map.put("type", "input_audio_buffer.append");
        map.put("audio", twilioMediaMessage.getMedia().getPayload());
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(map);
        this.callManager.sendOpenAiRealtimeSession(twilioMediaEventDto, json);
    }

    @EventListener
    public  void handleOpenAiEvent(OpenAiAudioEvent openAiAudioEvent) throws IOException {
        log.info("open Ai audio event Received - {}", openAiAudioEvent);
        OpenAiAudioDto openAiAudioDto = openAiAudioEvent.getOpenAiAudioDto();
        Map<String,Object> audioDelta = new HashMap<>();
        audioDelta.put("event","media");
        audioDelta.put("streamSid",openAiAudioEvent.getTwilioStartEventDto().getTwilioStartMediaMessage().getStreamSid());
        Map<String, String> audioData = new HashMap<>();
        byte[] audioBytes = Base64.getDecoder().decode(openAiAudioDto.getDelta());
        String reEncodedBase64AudioBuffer = Base64.getEncoder().encodeToString(audioBytes);
        audioData.put("payload", reEncodedBase64AudioBuffer);
        this.callManager.sendTwilioRealtimeSession(openAiAudioEvent, audioDelta, audioData);
    }

    @EventListener
    public  void handleOpenResponseDoneAiEvent(OpenAiResponseDoneEvent openAiResponseDoneEventDto) {
        log.info("open Ai event Received - {}", openAiResponseDoneEventDto);
        this.callManager.addBillingInformation(openAiResponseDoneEventDto.getOpenAiResponseDoneDto(),
                openAiResponseDoneEventDto.getTwilioStartEventDto());
    }

    @EventListener
    public  void handleOpenAiEvent(OpenAiEventDto openAiEventDto) {
        log.info("open Ai event Received - {}", openAiEventDto);
    }

    @EventListener
    public  void handleOpenAiEvent(OpenAiSessionCreateEvent sessionCreateEvent) {
        log.info("open Ai create event Received - {}", sessionCreateEvent);
    }

    @EventListener
    public void handleTwilioSessionEvent(TwilioSessionEvent twilioSessionEvent) {


    }

    @EventListener
    public void handleTwilioCloseEvent(TwilioCloseEvent twilioSessionEvent) {
        WebSocketSession websocketSession = twilioSessionEvent.getSession();
        Map<String, Object> attributes = websocketSession.getAttributes();
        int leadId = (int) attributes.get("leadId");
        int campaignRunId = (int) attributes.get("campaignRunId");
        String callType = (String) attributes.get("callType");
        this.callManager.removeSession(leadId,campaignRunId,callType);
    }


    @EventListener
    public  void handleOpenAiEvent(OpenAiSessionUpdateEvent sessionUpdateEvent) {
        log.info("open Ai update event Received - {}", sessionUpdateEvent);
    }

    public void runCampaign(int campaignRunId, int businessId) {
        CampaignRunData campaignRunData = this.campaignManager.getCampaignRunData(campaignRunId, businessId);
        TwilioData twilioData = this.metaManager.getTwilioData(campaignRunData.getBusinessId(), campaignRunData.getPhoneId());
        CampaignData campaignData =this.campaignManager.getCampaignData(campaignRunData.getCampaignId(), campaignRunData.getBusinessId());
        List<Integer> leadList =this.campaignManager.getLeadListByCampaignRunId(campaignRunData.getCampaignRunId());
        List<LeadData> leadDataList = this.leadManager.getLeadDataByList(businessId, new HashSet<>(leadList));

        for(LeadData leadData: leadDataList) {
            try{
                this.callManager.makeCall(twilioData, leadData, campaignData, campaignRunData,"OUT_BOUND" );
            }
            catch (Exception e){
                log.error("Failed to call log ", e);
            }
        }

    }

    @Override
    public AgentResponseDto addAgent(AgentRequestDto agentRequestDto) {
        this.getBusinessData(agentRequestDto.getBusinessId());
        return this.agentManager.addAgent(agentRequestDto);
    }


    @Override
    public List<AgentResponseDto> findByBusinessId(int businessId) {
        this.getBusinessData(businessId);
        return this.agentManager.findByBusinessId(businessId);
    }

    @Transactional
    public OnBoardingResponseDto onBoardBusiness(OnBoardingDto onBoardingDto){
        BusinessDataIndia businessDataIndiaModel = businessDataIndiaModelToModel(onBoardingDto);
        BusinessData businessData = getBusinessData(onBoardingDto.getParentBusinessId());
        BusinessDataIndia businessDataIndia = businessIndiaService.addBusinessData(businessDataIndiaModel);
        return getOnBoardingResponseDto(businessData, businessDataIndia, createBusinessDetailsFromModels(businessDataIndiaModel));
    }

    public OnBoardingResponseDto getOnBoardBusiness(int businessId){
        BusinessData businessData = getBusinessData(businessId);
        BusinessDataIndia businessDataIndia = businessIndiaService.getBusinessData(businessId);
        return getOnBoardingResponseDto(businessData,  businessDataIndia, createBusinessDetailsFromModels(businessDataIndia));
    }

    @Override
    public TwilioSubAccountDto generateNumber(GenerateNumberRequestDto generateNumberRequestDto) {
        BusinessData businessData = getBusinessData(generateNumberRequestDto.getBusinessId());
        TwilioData twilioData = new TwilioData();
        twilioData.setFriendlyName(businessData.getBusinessName());
        return this.phoneService.createSubAccount(twilioData);
    }


    private OnBoardingDto.BusinessDetails createBusinessDetailsFromModels(BusinessDataIndia businessDataIndia){
        OnBoardingDto.BusinessDetails businessDetails = new OnBoardingDto.BusinessDetails();
        if(businessDataIndia.getAdhaarNumber()!=null) businessDetails.setAdhaarNumber(businessDataIndia.getAdhaarNumber());
        if(businessDataIndia.getGstIn()!=null)  businessDetails.setGstNumber(businessDataIndia.getGstIn());
        if(businessDataIndia.getUdyamRegistrationNumber()!=null) businessDetails.setUdyamRegistrationNumber(businessDataIndia.getUdyamRegistrationNumber());
        if(businessDataIndia.getPan()!=null) businessDetails.setPan(businessDataIndia.getPan());
        if(businessDataIndia.getPhone()!=null) businessDetails.setPhone(businessDataIndia.getPhone());
        if(businessDataIndia.getAddress()!=null) businessDetails.setCountryCode(businessDataIndia.getAddress().getCountryCode());
        return businessDetails;
    }

    private OnBoardingResponseDto getOnBoardingResponseDto(BusinessData businessData,  BusinessDataIndia businessDataIndia, OnBoardingDto.BusinessDetails businessDetailsFromModels) {
        OnBoardingResponseDto onBoardingResponseDto = new OnBoardingResponseDto();
        onBoardingResponseDto.setBusinessId(businessDataIndia.getBusinessId());
        onBoardingResponseDto.setParentBusinessId(businessData.getBusinessId());
        if(businessData.getBusinessName()!= null)  onBoardingResponseDto.setBusinessName(businessData.getBusinessName());
        onBoardingResponseDto.setBusinessDetails(businessDetailsFromModels);
        onBoardingResponseDto.setPhoneGenerated(businessDataIndia.isPhoneGenerated());
        if(businessDataIndia.getAddress()!= null) onBoardingResponseDto.setAddress(getAddress(businessDataIndia.getAddress()));
        if(businessDataIndia.getBankDetails()!= null) onBoardingResponseDto.setBankDetails(getBankDetails(businessDataIndia.getBankDetails()));
        return onBoardingResponseDto;
    }

    private OnBoardingDto.BankDetails getBankDetails(BankDetails bankDetailsModel) {
        OnBoardingDto.BankDetails bankDetails = new OnBoardingDto.BankDetails();
        bankDetails.setBankName(bankDetailsModel.getBankName());
        bankDetails.setAccountHolderName(bankDetailsModel.getAccountHolderName());
        bankDetails.setIfscCode(bankDetailsModel.getIfscCode());
        bankDetails.setAccountNumber(bankDetailsModel.getAccountNumber());
        bankDetails.setBranchName(bankDetails.getBranchName());
        return bankDetails;
    }

    private OnBoardingDto.Address getAddress(Address addressModel) {
        OnBoardingDto.Address address = new OnBoardingDto.Address();
        address.setStreetAddress(addressModel.getStreet());
        address.setApartment(addressModel.getApartment());
        OnBoardingDto.Region countryRegion =  new OnBoardingDto.Region();
        countryRegion.setIsoCode(addressModel.getCountryCode());
        address.setCountry(countryRegion);
        OnBoardingDto.Region stateRegion =  new OnBoardingDto.Region();
        stateRegion.setIsoCode(addressModel.getState());
        address.setState(stateRegion);
        address.setFormattedAddress(addressModel.getFormattedAddress());
        address.setPostalCode(addressModel.getZipCode());
        OnBoardingDto.Region cityRegion =  new OnBoardingDto.Region();
        cityRegion.setIsoCode(addressModel.getState());
        address.setCity(cityRegion);
        return address;
    }


    public BusinessData getBusinessByEmail(String email) {
        return this.businessService.getBusinessDataByEmail(email);
    }

    public BusinessData getBusinessByMobile(String mobile) {
        return this.businessService.getBusinessDataByMobile(mobile);
    }

    @Override
    public boolean checkEmailExists(String email) {
        return this.businessService.checkEmailExists(email);
    }



    @Override
    public boolean checkPhoneExists(String phone) {
        return this.businessService.checkPhoneExists(phone);
    }

    @Override
    public List<BusinessSizeMasterDataDto> getAllBusinessSizeMasterData() {
        return this.masterDataService.getAllBusinessSize().stream().map(this::businessSizeMasterDataModelToDto).toList();
    }

    private BusinessSizeMasterDataDto businessSizeMasterDataModelToDto(BusinessSizeMasterData businessSizeMasterData) {
        BusinessSizeMasterDataDto businessSizeMasterDataDto = new BusinessSizeMasterDataDto();
        businessSizeMasterDataDto.setValue(businessSizeMasterData.getId());
        businessSizeMasterDataDto.setLabel(businessSizeMasterData.getLabel());
        return  businessSizeMasterDataDto;
    }




    private BusinessDataIndia businessDataIndiaModelToModel(OnBoardingDto onBoardingDto) {
        BusinessDataIndia businessDataIndiaModel =this.businessIndiaService.getBusinessData(onBoardingDto.getParentBusinessId());
        businessDataIndiaModel.setParentBusinessId(onBoardingDto.getParentBusinessId());
        if(onBoardingDto.getBusinessDetails() != null &&
                onBoardingDto.getBusinessDetails().getPan()!= null)
                    businessDataIndiaModel.setPan(onBoardingDto.getBusinessDetails().getPan());
        if(onBoardingDto.getBusinessDetails() != null &&
                onBoardingDto.getBusinessDetails().getAdhaarNumber()!= null) businessDataIndiaModel.setAdhaarNumber(onBoardingDto.getBusinessDetails().getAdhaarNumber());
        if(onBoardingDto.getBusinessDetails() != null &&
                onBoardingDto.getBusinessDetails().getGstNumber()!= null) businessDataIndiaModel.setGstIn(onBoardingDto.getBusinessDetails().getGstNumber());
        if(onBoardingDto.getBusinessDetails() != null &&
                onBoardingDto.getBusinessDetails().getUdyamRegistrationNumber()!= null) businessDataIndiaModel.setUdyamRegistrationNumber(onBoardingDto.getBusinessDetails().getUdyamRegistrationNumber());
        businessDataIndiaModel.setActive(true);
        if(onBoardingDto.getBusinessDetails() != null &&
                onBoardingDto.getBusinessDetails().getPhone()!= null) businessDataIndiaModel.setPhone(onBoardingDto.getBusinessDetails().getPhone());
        businessDataIndiaModel.setAddress(addressDtoToModel(onBoardingDto.getAddress()));
        if(onBoardingDto.getBankDetails() != null)
        {
            businessDataIndiaModel.setBankDetails(bankDetailsDtoToModel(onBoardingDto.getBankDetails()));
        };
        return businessDataIndiaModel;
    }

    private Address addressDtoToModel(OnBoardingDto.Address addressDto){
        Address addressModel = new Address();
        if(addressDto.getStreetAddress()!=null) addressModel.setStreet(addressDto.getStreetAddress());
        if(addressDto.getApartment()!=null)  addressModel.setApartment(addressDto.getApartment());
        if(addressDto.getCity()!=null) addressModel.setCity(addressDto.getCity().getName());
        if(addressDto.getState()!=null) addressModel.setState(addressDto.getState().getIsoCode());
        if(addressDto.getPostalCode()!=null) addressModel.setZipCode(addressDto.getPostalCode());
        if(addressDto.getCountry()!=null) addressModel.setCountryCode(addressDto.getCountry().getIsoCode());
        if(addressDto.getFormattedAddress()!=null) addressModel.setFormattedAddress(addressDto.getFormattedAddress());
        return addressModel;
    }

    private BankDetails bankDetailsDtoToModel(OnBoardingDto.BankDetails bankDetailsDto){
        BankDetails bankDetails = new BankDetails();
        if(bankDetailsDto.getBranchName()!=null) bankDetails.setBankBranch(bankDetailsDto.getBranchName());
        if(bankDetailsDto.getBankName()!=null) bankDetails.setBankName(bankDetailsDto.getBankName());
        if(bankDetailsDto.getAccountNumber()!=null)  bankDetails.setAccountNumber(bankDetailsDto.getAccountNumber());
        if(bankDetailsDto.getIfscCode()!=null) bankDetails.setIfscCode(bankDetailsDto.getIfscCode());
        if(bankDetailsDto.getAccountHolderName()!=null) bankDetails.setAccountHolderName(bankDetailsDto.getAccountHolderName());
        return bankDetails;
    }

    private DalaiLlamaLeads leadDtoToModel(DalaiLlamaLeadsDto dalaiLlamaLeadsDto){
        DalaiLlamaLeads dalaiLlamaLeads = new DalaiLlamaLeads();
        if(dalaiLlamaLeadsDto.getName()!= null) dalaiLlamaLeads.setName(dalaiLlamaLeadsDto.getName());
        if(dalaiLlamaLeadsDto.getEmail()!= null) dalaiLlamaLeads.setEmail(dalaiLlamaLeadsDto.getEmail());
        if(dalaiLlamaLeadsDto.getMobileNumber()!= null) dalaiLlamaLeads.setPhone(dalaiLlamaLeadsDto.getMobileNumber());
        if(dalaiLlamaLeadsDto.getCountryCode()!= null) dalaiLlamaLeads.setCountryCode(dalaiLlamaLeadsDto.getCountryCode());
        if(dalaiLlamaLeadsDto.getCompanySize()!= null) dalaiLlamaLeads.setCompanySize(dalaiLlamaLeadsDto.getCompanySize());
        if(dalaiLlamaLeadsDto.getCountryCallingCode()!= null) dalaiLlamaLeads.setCountryCallingCode(dalaiLlamaLeadsDto.getCountryCallingCode());
        //if(dalaiLlamaLeadsDto.getDescription()!= null) dalaiLlamaLeads.setDescription(dalaiLlamaLeadsDto.getDescription());
        return dalaiLlamaLeads;
    }

}
