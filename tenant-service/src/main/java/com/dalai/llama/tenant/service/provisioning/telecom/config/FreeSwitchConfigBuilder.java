package com.dalai.llama.tenant.service.provisioning.telecom.config;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Production-ready FreeSWITCH configuration builder with all CC features:
 * - Conference with moderator controls
 * - Barge/Whisper/Silent Monitor
 * - Skills-based routing
 * - Callback queue
 * - AI integrations
 * - Voicemail with transcription
 */
@Slf4j
@Component
public class FreeSwitchConfigBuilder {

    public String buildDirectory(Tenant tenant, TelecomStackConfig config) {
        log.info("Building FreeSWITCH directory for tenant {}", tenant.getSlug());

        return String.format("""
        <?xml version="1.0" encoding="UTF-8"?>
        <include>
          <domain name="%s">
            <params>
              <param name="dial-string" value="{presence_id=${dialed_user}@${dialed_domain}}${sofia_contact(${dialed_user}@${dialed_domain})}"/>
              <param name="jsonrpc-allowed-methods" value="verto"/>
            </params>
            <variables>
              <variable name="record_stereo" value="%s"/>
              <variable name="default_gateway" value="kamailio"/>
              <variable name="default_areacode" value="91"/>
              <variable name="transfer_fallback_extension" value="operator"/>
              <variable name="tenant_id" value="%s"/>
            </variables>
            <groups>
              <group name="default">
                <users>
                  <!-- Users are dynamically fetched from PBX-Core via mod_xml_curl -->
                </users>
              </group>
              <group name="agents">
                <users>
                  <!-- Agent users -->
                </users>
              </group>
              <group name="supervisors">
                <users>
                  <!-- Supervisor users -->
                </users>
              </group>
            </groups>
          </domain>
        </include>
        """,
                config.getFreeSwitchInternalDomain(),
                config.isRecordingStereo(),
                config.getTenantId()
        );
    }

    public String buildDialplan(Tenant tenant, TelecomStackConfig config) {
        log.info("Building FreeSWITCH dialplan for tenant {}", tenant.getSlug());
        
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<include>\n");
        sb.append("  <!-- FreeSWITCH Dialplan for Tenant: ").append(tenant.getSlug()).append(" -->\n");
        sb.append("  <!-- Generated: ").append(java.time.Instant.now()).append(" -->\n\n");
        sb.append("  <context name=\"default\">\n\n");
        
        sb.append(buildInboundExtension(config));
        sb.append(buildIvrExtensions(config));
        sb.append(buildQueueExtensions(config));
        sb.append(buildAgentExtensions(config));
        sb.append(buildConferenceExtensions(config));
        sb.append(buildSupervisorExtensions(config));
        sb.append(buildTransferExtensions(config));
        sb.append(buildVoicemailExtensions(config));
        sb.append(buildDialerExtensions(config));
        sb.append(buildAiExtensions(config));
        sb.append(buildUtilityExtensions(config));
        
        sb.append("  </context>\n\n");
        sb.append("  <context name=\"public\">\n");
        sb.append("    <extension name=\"public_inbound\">\n");
        sb.append("      <condition field=\"destination_number\" expression=\"^(.*)$\">\n");
        sb.append("        <action application=\"transfer\" data=\"$1 XML default\"/>\n");
        sb.append("      </condition>\n");
        sb.append("    </extension>\n");
        sb.append("  </context>\n\n");
        sb.append("</include>\n");
        
        return sb.toString();
    }
    
    private String buildInboundExtension(TelecomStackConfig config) {
        return String.format("""
            
                <!-- ============================================================ -->
                <!-- INBOUND CALL HANDLING                                        -->
                <!-- ============================================================ -->
                
                <extension name="inbound_main">
                  <condition field="destination_number" expression="^inbound$|^(\\+?[0-9]+)$">
                    <action application="log" data="INFO [INBOUND] ${caller_id_number} -> ${destination_number}"/>
                    <action application="set" data="tenant_id=%s"/>
                    <action application="set" data="tenant_realm=%s"/>
                    <action application="set" data="call_direction=inbound"/>
                    <action application="set" data="call_start_time=${strftime(%%Y-%%m-%%d %%H:%%M:%%S)}"/>
                    <action application="hash" data="insert/${tenant_id}-calls/${uuid}/${caller_id_number}"/>
                    
                    <!-- Recording setup -->
                    %s
                    
                    <!-- AI transcription -->
                    %s
                    
                    <!-- Screen pop webhook -->
                    %s
                    
                    <!-- Get routing from PBX-Core -->
                    <action application="set" data="route_response=${curl(%s/v1/routing/${tenant_id}/inbound?did=${destination_number}&caller=${caller_id_number}&uuid=${uuid})}"/>
                    <action application="set" data="route_type=${curl_response_code}"/>
                    
                    <!-- Route based on response headers -->
                    <action application="set" data="ivr_flow=${sip_h_X-IVR-Flow}"/>
                    <action application="set" data="queue_id=${sip_h_X-Queue-Id}"/>
                    <action application="set" data="agent_id=${sip_h_X-Agent-Id}"/>
                    
                    <action application="execute_extension" data="route_decision"/>
                  </condition>
                </extension>
                
                <extension name="route_decision">
                  <condition field="${ivr_flow}" expression="^.+$" break="on-true">
                    <action application="transfer" data="ivr_${ivr_flow} XML default"/>
                  </condition>
                  <condition field="${agent_id}" expression="^.+$" break="on-true">
                    <action application="transfer" data="agent_${agent_id} XML default"/>
                  </condition>
                  <condition field="${queue_id}" expression="^.+$" break="on-true">
                    <action application="transfer" data="queue_${queue_id} XML default"/>
                  </condition>
                  <condition>
                    <action application="transfer" data="ivr_main XML default"/>
                  </condition>
                </extension>
                
            """,
            config.getTenantId(),
            config.getRealm(),
            buildRecordingActions(config),
            buildAiTranscriptionAction(config),
            buildScreenPopAction(config),
            config.getPbxCoreUrl()
        );
    }
    
    private String buildRecordingActions(TelecomStackConfig config) {
        if (!config.isEnableRecording()) {
            return "<!-- Recording disabled -->";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<action application=\"set\" data=\"RECORD_STEREO=").append(config.isRecordingStereo()).append("\"/>\n");
        sb.append("                    <action application=\"set\" data=\"record_file=").append(config.getRecordingPath())
          .append("/${tenant_id}/${strftime(%%Y/%%m/%%d)}/${uuid}.").append(config.getRecordingFormat()).append("\"/>\n");
        sb.append("                    <action application=\"record_session\" data=\"${record_file}\"/>");
        if (config.isRecordingPciPause()) {
            sb.append("\n                    <action application=\"set\" data=\"recording_pci_pause_enabled=true\"/>");
        }
        return sb.toString();
    }
    
    private String buildAiTranscriptionAction(TelecomStackConfig config) {
        if (!config.isEnableAiTranscription()) {
            return "<!-- AI transcription disabled -->";
        }
        return String.format(
            "<action application=\"audio_fork\" data=\"start %s/stream?uuid=${uuid}&amp;tenant=${tenant_id}&amp;features=%s\"/>",
            config.getAiServiceUrl(),
            config.getAiFeatureFlags()
        );
    }
    
    private String buildScreenPopAction(TelecomStackConfig config) {
        if (!config.isEnableScreenPop() || config.getCrmWebhookUrl() == null) {
            return "<!-- Screen pop disabled -->";
        }
        return String.format(
            "<action application=\"curl\" data=\"%s post tenant=${tenant_id}&amp;caller=${caller_id_number}&amp;uuid=${uuid}&amp;did=${destination_number}\"/>",
            config.getCrmWebhookUrl()
        );
    }
    
    private String buildIvrExtensions(TelecomStackConfig config) {
        if (!config.isEnableIvr()) {
            return "<!-- IVR disabled -->\n";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("""
            
                <!-- ============================================================ -->
                <!-- IVR EXTENSIONS                                               -->
                <!-- ============================================================ -->
                
                <extension name="ivr_main">
                  <condition field="destination_number" expression="^ivr_main$|^ivr$">
                    <action application="answer"/>
                    <action application="sleep" data="500"/>
            """);
        
        if (config.isEnableConversationalIvr()) {
            sb.append(String.format("""
                        <!-- Conversational AI IVR -->
                        <action application="set" data="tts_engine=elevenlabs"/>
                        <action application="set" data="stt_engine=whisper"/>
                        <action application="audio_fork" data="start %s/ivr-bot?tenant=${tenant_id}&amp;uuid=${uuid}&amp;lang=%s"/>
                        <action application="playback" data="silence_stream://300000,1400"/>
                  </condition>
                </extension>
                
                """, config.getAiServiceUrl(), config.getIvrDefaultLanguage()));
        } else {
            sb.append("""
                        <!-- DTMF IVR -->
                        <action application="play_and_get_digits" 
                                data="1 1 3 5000 # ivr/ivr-welcome.wav ivr/ivr-that_was_an_invalid_entry.wav ivr_selection [0-9*#]"/>
                        <action application="transfer" data="ivr_route_${ivr_selection} XML default"/>
                  </condition>
                </extension>
                
                <extension name="ivr_route_1">
                  <condition field="destination_number" expression="^ivr_route_1$">
                    <action application="playback" data="ivr/ivr-please_hold.wav"/>
                    <action application="transfer" data="queue_sales XML default"/>
                  </condition>
                </extension>
                
                <extension name="ivr_route_2">
                  <condition field="destination_number" expression="^ivr_route_2$">
                    <action application="playback" data="ivr/ivr-please_hold.wav"/>
                    <action application="transfer" data="queue_support XML default"/>
                  </condition>
                </extension>
                
                <extension name="ivr_route_0">
                  <condition field="destination_number" expression="^ivr_route_0$">
                    <action application="transfer" data="queue_general XML default"/>
                  </condition>
                </extension>
                
                """);
        }
        
        // Dynamic IVR node from PBX-Core
        sb.append(String.format("""
                <!-- Dynamic IVR node -->
                <extension name="ivr_dynamic">
                  <condition field="destination_number" expression="^ivr_([a-zA-Z0-9_-]+)$">
                    <action application="set" data="ivr_node_id=$1"/>
                    <action application="answer"/>
                    <action application="lua" data="ivr_handler.lua ${ivr_node_id} %s"/>
                  </condition>
                </extension>
                
            """, config.getPbxCoreUrl()));
        
        return sb.toString();
    }
    
    private String buildQueueExtensions(TelecomStackConfig config) {
        if (!config.isEnableQueues()) {
            return "<!-- Queues disabled -->\n";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("""
            
                <!-- ============================================================ -->
                <!-- QUEUE HANDLING                                               -->
                <!-- ============================================================ -->
                
                <extension name="queue_enter">
                  <condition field="destination_number" expression="^queue_([a-zA-Z0-9_-]+)$">
                    <action application="set" data="queue_name=$1"/>
                    <action application="set" data="queue_entry_time=${strftime(%%s)}"/>
                    <action application="answer"/>
                    
                    <!-- Notify PBX-Core -->
                    <action application="curl" data="%s/v1/queues/${tenant_id}/${queue_name}/enter post uuid=${uuid}&amp;caller=${caller_id_number}&amp;priority=${queue_priority:-5}"/>
                    
                    <!-- Play position announcement -->
                    <action application="playback" data="ivr/ivr-please_hold.wav"/>
                    <action application="set" data="fifo_announce=ivr/ivr-you_are_number.wav"/>
                    
                    <!-- Enter FIFO with MOH -->
                    <action application="fifo" data="${queue_name}@${tenant_id} in $${hold_music}"/>
                  </condition>
                </extension>
                
                <extension name="queue_agent_pickup">
                  <condition field="destination_number" expression="^queue_pickup_([a-zA-Z0-9_-]+)$">
                    <action application="set" data="queue_name=$1"/>
                    <action application="fifo" data="${queue_name}@${tenant_id} out nowait"/>
                  </condition>
                </extension>
                
            """, config.getPbxCoreUrl()));
        
        // Callback queue
        if (config.isEnableCallbackQueue()) {
            sb.append(String.format("""
                <!-- Callback Queue -->
                <extension name="queue_callback_request">
                  <condition field="destination_number" expression="^callback_request$">
                    <action application="answer"/>
                    <action application="playback" data="ivr/ivr-callback_registered.wav"/>
                    <action application="curl" data="%s/v1/callback/${tenant_id}/register post caller=${caller_id_number}&amp;queue=${queue_name}&amp;uuid=${uuid}"/>
                    <action application="playback" data="ivr/ivr-goodbye.wav"/>
                    <action application="hangup"/>
                  </condition>
                </extension>
                
                <extension name="queue_callback_execute">
                  <condition field="destination_number" expression="^callback_([a-zA-Z0-9_-]+)$">
                    <action application="set" data="callback_id=$1"/>
                    <action application="set" data="effective_caller_id_number=${callback_cli}"/>
                    <action application="bridge" data="sofia/external/${callback_target}@kamailio:5060"/>
                  </condition>
                </extension>
                
            """, config.getPbxCoreUrl()));
        }
        
        // Skills-based routing
        if (config.isEnableSkillsRouting()) {
            sb.append(String.format("""
                <!-- Skills-based Routing -->
                <extension name="queue_skills">
                  <condition field="destination_number" expression="^queue_skills_([a-zA-Z0-9_-]+)$">
                    <action application="set" data="skill_group=$1"/>
                    <action application="set" data="best_agent=${curl(%s/v1/routing/${tenant_id}/skills/${skill_group}?caller=${caller_id_number})}"/>
                    <action application="transfer" data="agent_${best_agent} XML default"/>
                  </condition>
                </extension>
                
            """, config.getPbxCoreUrl()));
        }
        
        return sb.toString();
    }
    
    private String buildAgentExtensions(TelecomStackConfig config) {
        return String.format("""
            
                <!-- ============================================================ -->
                <!-- AGENT HANDLING                                               -->
                <!-- ============================================================ -->
                
                <extension name="agent_bridge">
                  <condition field="destination_number" expression="^agent_([a-zA-Z0-9_@.-]+)$">
                    <action application="set" data="target_agent=$1"/>
                    <action application="log" data="INFO [AGENT] Bridging to: ${target_agent}"/>
                    
                    <!-- Get agent contact from PBX-Core -->
                    <action application="set" data="agent_contact=${curl(%s/v1/agents/${tenant_id}/${target_agent}/contact)}"/>
                    <action application="set" data="agent_status=${curl_response_code}"/>
                    
                    <!-- Set timeouts -->
                    <action application="set" data="call_timeout=30"/>
                    <action application="set" data="continue_on_fail=true"/>
                    <action application="set" data="hangup_after_bridge=true"/>
                    
                    <!-- Ringback -->
                    <action application="set" data="ringback=${us-ring}"/>
                    
                    <!-- Bridge to agent -->
                    <action application="bridge" data="sofia/external/${agent_contact}"/>
                    
                    <!-- If agent doesn't answer, go to voicemail or next action -->
                    <action application="transfer" data="agent_noanswer_${target_agent} XML default"/>
                  </condition>
                </extension>
                
                <extension name="agent_noanswer">
                  <condition field="destination_number" expression="^agent_noanswer_([a-zA-Z0-9_@.-]+)$">
                    <action application="set" data="target_agent=$1"/>
                    <action application="curl" data="%s/v1/agents/${tenant_id}/${target_agent}/noanswer post uuid=${uuid}"/>
                    <action application="transfer" data="vm_${target_agent} XML default"/>
                  </condition>
                </extension>
                
                <!-- Ring group -->
                <extension name="agent_group">
                  <condition field="destination_number" expression="^group_([a-zA-Z0-9_-]+)$">
                    <action application="set" data="group_id=$1"/>
                    <action application="set" data="agent_list=${curl(%s/v1/groups/${tenant_id}/${group_id}/agents)}"/>
                    <action application="set" data="call_timeout=25"/>
                    <action application="bridge" data="${agent_list}"/>
                  </condition>
                </extension>
                
                <!-- PSTN outbound -->
                <extension name="pstn_outbound">
                  <condition field="destination_number" expression="^(\\+?[0-9]{10,15})$">
                    <action application="set" data="pstn_number=$1"/>
                    <action application="log" data="INFO [OUTBOUND] ${caller_id_number} -> ${pstn_number}"/>
                    <action application="set" data="call_direction=outbound"/>
                    %s
                    %s
                    <action application="bridge" data="sofia/external/${pstn_number}@kamailio:5060"/>
                  </condition>
                </extension>
                
            """,
            config.getPbxCoreUrl(),
            config.getPbxCoreUrl(),
            config.getPbxCoreUrl(),
            config.isEnableRecording() ? "<action application=\"record_session\" data=\"${record_file}\"/>" : "",
            config.isEnableDncList() ? 
                String.format("<action application=\"set\" data=\"dnc_check=${curl(%s/v1/dnc/${tenant_id}/check?number=${pstn_number})}\"/>", config.getPbxCoreUrl()) : ""
        );
    }
    
    private String buildConferenceExtensions(TelecomStackConfig config) {
        if (!config.isEnableConference()) {
            return "<!-- Conference disabled -->\n";
        }
        
        return String.format("""
            
                <!-- ============================================================ -->
                <!-- CONFERENCE CALLING                                           -->
                <!-- ============================================================ -->
                
                <!-- Join conference as participant -->
                <extension name="conference_join">
                  <condition field="destination_number" expression="^conf_([a-zA-Z0-9_-]+)$">
                    <action application="answer"/>
                    <action application="set" data="conference_id=$1"/>
                    <action application="set" data="conference_member_id=${uuid}"/>
                    <action application="log" data="INFO [CONFERENCE] ${caller_id_number} joining ${conference_id}"/>
                    
                    <!-- Notify PBX-Core -->
                    <action application="curl" data="%s/v1/conference/${tenant_id}/${conference_id}/join post member=${uuid}&amp;caller=${caller_id_number}"/>
                    
                    %s
                    
                    <action application="playback" data="conference/conf-welcome.wav"/>
                    <action application="conference" data="${conference_id}@%s-conf"/>
                  </condition>
                </extension>
                
                <!-- Join as moderator -->
                <extension name="conference_moderator">
                  <condition field="destination_number" expression="^conf_mod_([a-zA-Z0-9_-]+)$">
                    <action application="answer"/>
                    <action application="set" data="conference_id=$1"/>
                    <action application="set" data="conference_member_flags=moderator"/>
                    <action application="log" data="INFO [CONFERENCE] Moderator joining ${conference_id}"/>
                    
                    <action application="curl" data="%s/v1/conference/${tenant_id}/${conference_id}/join post member=${uuid}&amp;role=moderator"/>
                    
                    %s
                    
                    <action application="conference" data="${conference_id}@%s-conf+flags{moderator|mute-detect}"/>
                  </condition>
                </extension>
                
                <!-- Dial out from conference -->
                <extension name="conference_dialout">
                  <condition field="destination_number" expression="^conf_dial_([a-zA-Z0-9_-]+)_(.+)$">
                    <action application="set" data="conference_id=$1"/>
                    <action application="set" data="dial_target=$2"/>
                    <action application="set" data="effective_caller_id_number=${conference_cli}"/>
                    <action application="bridge" data="sofia/external/${dial_target}@kamailio:5060&amp;conference:${conference_id}@%s-conf"/>
                  </condition>
                </extension>
                
                <!-- Conference controls (via API/ESL) -->
                <extension name="conference_mute">
                  <condition field="destination_number" expression="^conf_mute_([a-zA-Z0-9_-]+)_(.+)$">
                    <action application="conference" data="$1 mute $2"/>
                  </condition>
                </extension>
                
                <extension name="conference_unmute">
                  <condition field="destination_number" expression="^conf_unmute_([a-zA-Z0-9_-]+)_(.+)$">
                    <action application="conference" data="$1 unmute $2"/>
                  </condition>
                </extension>
                
                <extension name="conference_kick">
                  <condition field="destination_number" expression="^conf_kick_([a-zA-Z0-9_-]+)_(.+)$">
                    <action application="conference" data="$1 kick $2"/>
                  </condition>
                </extension>
                
            """,
            config.getPbxCoreUrl(),
            config.isConferenceRecording() ? 
                "<action application=\"set\" data=\"conference_auto_record=" + config.getRecordingPath() + "/conf/${conference_id}_${strftime(%%Y%%m%%d-%%H%%M%%S)}.wav\"/>" : "",
            config.getTenantSlug(),
            config.getPbxCoreUrl(),
            config.isConferenceRecording() ? 
                "<action application=\"set\" data=\"conference_auto_record=" + config.getRecordingPath() + "/conf/${conference_id}_${strftime(%%Y%%m%%d-%%H%%M%%S)}.wav\"/>" : "",
            config.getTenantSlug(),
            config.getTenantSlug()
        );
    }
    
    private String buildSupervisorExtensions(TelecomStackConfig config) {
        if (!config.isEnableSupervisorFeatures()) {
            return "<!-- Supervisor features disabled -->\n";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("""
            
                <!-- ============================================================ -->
                <!-- SUPERVISOR: BARGE / WHISPER / MONITOR                        -->
                <!-- ============================================================ -->
                
            """));
        
        // Silent Monitor
        if (config.isEnableSilentMonitor()) {
            sb.append(String.format("""
                <!-- Silent Monitor - Supervisor can listen to agent's call -->
                <extension name="supervisor_monitor">
                  <condition field="destination_number" expression="^spy_([a-zA-Z0-9_-]+)$">
                    <action application="answer"/>
                    <action application="set" data="target_uuid=$1"/>
                    <action application="log" data="INFO [SUPERVISOR] Monitor: ${caller_id_number} spying on ${target_uuid}"/>
                    <action application="curl" data="%s/v1/supervisor/${tenant_id}/monitor post supervisor=${caller_id_number}&amp;target=${target_uuid}"/>
                    <action application="eavesdrop" data="${target_uuid}"/>
                  </condition>
                </extension>
                
            """, config.getPbxCoreUrl()));
        }
        
        // Whisper
        if (config.isEnableWhisper()) {
            sb.append(String.format("""
                <!-- Whisper - Supervisor speaks only to agent, caller can't hear -->
                <extension name="supervisor_whisper">
                  <condition field="destination_number" expression="^whisper_([a-zA-Z0-9_-]+)$">
                    <action application="answer"/>
                    <action application="set" data="target_uuid=$1"/>
                    <action application="set" data="eavesdrop_whisper_aleg=true"/>
                    <action application="set" data="eavesdrop_whisper_bleg=false"/>
                    <action application="log" data="INFO [SUPERVISOR] Whisper: ${caller_id_number} -> ${target_uuid}"/>
                    <action application="curl" data="%s/v1/supervisor/${tenant_id}/whisper post supervisor=${caller_id_number}&amp;target=${target_uuid}"/>
                    <action application="eavesdrop" data="${target_uuid}"/>
                  </condition>
                </extension>
                
            """, config.getPbxCoreUrl()));
        }
        
        // Barge
        if (config.isEnableBarge()) {
            sb.append(String.format("""
                <!-- Barge - Supervisor joins call, all parties can hear -->
                <extension name="supervisor_barge">
                  <condition field="destination_number" expression="^barge_([a-zA-Z0-9_-]+)$">
                    <action application="answer"/>
                    <action application="set" data="target_uuid=$1"/>
                    <action application="log" data="INFO [SUPERVISOR] Barge: ${caller_id_number} -> ${target_uuid}"/>
                    <action application="curl" data="%s/v1/supervisor/${tenant_id}/barge post supervisor=${caller_id_number}&amp;target=${target_uuid}"/>
                    <action application="three_way" data="${target_uuid}"/>
                  </condition>
                </extension>
                
            """, config.getPbxCoreUrl()));
        }
        
        // Takeover
        if (config.isEnableTakeover()) {
            sb.append(String.format("""
                <!-- Takeover - Supervisor takes over call from agent -->
                <extension name="supervisor_takeover">
                  <condition field="destination_number" expression="^takeover_([a-zA-Z0-9_-]+)$">
                    <action application="set" data="target_uuid=$1"/>
                    <action application="log" data="INFO [SUPERVISOR] Takeover: ${caller_id_number} -> ${target_uuid}"/>
                    <action application="curl" data="%s/v1/supervisor/${tenant_id}/takeover post supervisor=${caller_id_number}&amp;target=${target_uuid}"/>
                    <action application="intercept" data="${target_uuid}"/>
                  </condition>
                </extension>
                
            """, config.getPbxCoreUrl()));
        }
        
        return sb.toString();
    }
    
    private String buildTransferExtensions(TelecomStackConfig config) {
        return String.format("""
            
                <!-- ============================================================ -->
                <!-- CALL TRANSFER                                                -->
                <!-- ============================================================ -->
                
                <!-- Blind transfer -->
                <extension name="transfer_blind">
                  <condition field="destination_number" expression="^xfer_([a-zA-Z0-9_@.-]+)$">
                    <action application="set" data="transfer_target=$1"/>
                    <action application="log" data="INFO [TRANSFER] Blind: ${uuid} -> ${transfer_target}"/>
                    <action application="curl" data="%s/v1/calls/${tenant_id}/${uuid}/transfer post type=blind&amp;target=${transfer_target}"/>
                    <action application="transfer" data="agent_${transfer_target} XML default"/>
                  </condition>
                </extension>
                
                <!-- Attended transfer - consult first -->
                <extension name="transfer_attended_start">
                  <condition field="destination_number" expression="^att_xfer_([a-zA-Z0-9_@.-]+)$">
                    <action application="set" data="transfer_target=$1"/>
                    <action application="set" data="park_uuid=${uuid}"/>
                    <action application="log" data="INFO [TRANSFER] Attended consult: ${uuid} -> ${transfer_target}"/>
                    <action application="park"/>
                  </condition>
                </extension>
                
                <!-- Complete attended transfer -->
                <extension name="transfer_attended_complete">
                  <condition field="destination_number" expression="^att_complete_([a-zA-Z0-9_-]+)$">
                    <action application="set" data="parked_uuid=$1"/>
                    <action application="uuid_bridge" data="${parked_uuid} ${uuid}"/>
                  </condition>
                </extension>
                
            """, config.getPbxCoreUrl());
    }
    
    private String buildVoicemailExtensions(TelecomStackConfig config) {
        if (!config.isEnableVoicemail()) {
            return "<!-- Voicemail disabled -->\n";
        }
        
        return String.format("""
            
                <!-- ============================================================ -->
                <!-- VOICEMAIL                                                    -->
                <!-- ============================================================ -->
                
                <extension name="voicemail_leave">
                  <condition field="destination_number" expression="^vm_([a-zA-Z0-9_@.-]+)$">
                    <action application="set" data="vm_user=$1"/>
                    <action application="answer"/>
                    <action application="playback" data="voicemail/vm-not_available.wav"/>
                    <action application="set" data="vm_file=%s/voicemail/${tenant_id}/${vm_user}/${uuid}.wav"/>
                    <action application="playback" data="voicemail/vm-record_message.wav"/>
                    <action application="set" data="playback_terminators=#"/>
                    <action application="record" data="${vm_file} %d 200 5"/>
                    
                    <!-- Notify PBX-Core -->
                    <action application="curl" data="%s/v1/voicemail/${tenant_id}/${vm_user} post file=${vm_file}&amp;caller=${caller_id_number}&amp;duration=${record_seconds}"/>
                    
                    %s
                    
                    <action application="playback" data="voicemail/vm-goodbye.wav"/>
                    <action application="hangup"/>
                  </condition>
                </extension>
                
                <extension name="voicemail_check">
                  <condition field="destination_number" expression="^vmcheck_([a-zA-Z0-9_@.-]+)$">
                    <action application="answer"/>
                    <action application="voicemail" data="check default ${tenant_id} $1"/>
                  </condition>
                </extension>
                
            """,
            config.getRecordingPath(),
            config.getVoicemailMaxDuration(),
            config.getPbxCoreUrl(),
            config.isVoicemailTranscription() ? 
                String.format("<action application=\"curl\" data=\"%s/v1/voicemail/${tenant_id}/${vm_user}/transcribe post file=${vm_file}\"/>", config.getAiServiceUrl()) : ""
        );
    }
    
    private String buildDialerExtensions(TelecomStackConfig config) {
        if (!config.isEnableOutboundDialer()) {
            return "<!-- Outbound dialer disabled -->\n";
        }
        
        return String.format("""
            
                <!-- ============================================================ -->
                <!-- OUTBOUND DIALER                                              -->
                <!-- ============================================================ -->
                
                <!-- Click-to-call / Originate -->
                <extension name="dialer_originate">
                  <condition field="destination_number" expression="^originate_([a-zA-Z0-9_-]+)$">
                    <action application="set" data="campaign_id=$1"/>
                    <action application="set" data="ringback=${us-ring}"/>
                    <action application="set" data="originate_timeout=30"/>
                    <action application="set" data="effective_caller_id_number=${originate_cli}"/>
                    <action application="bridge" data="user/${originate_agent}"/>
                    <action application="set" data="call_direction=outbound"/>
                    <action application="bridge" data="sofia/external/${originate_target}@kamailio:5060"/>
                  </condition>
                </extension>
                
                <!-- Preview dial - agent reviews before call -->
                <extension name="dialer_preview">
                  <condition field="destination_number" expression="^preview_dial$">
                    <action application="set" data="effective_caller_id_number=${campaign_cli}"/>
                    <action application="curl" data="%s/v1/dialer/${tenant_id}/${campaign_id}/started post uuid=${uuid}&amp;target=${dial_target}"/>
                    <action application="bridge" data="sofia/external/${dial_target}@kamailio:5060"/>
                  </condition>
                </extension>
                
                <!-- Predictive dial callback from dialer engine -->
                <extension name="dialer_predictive_connect">
                  <condition field="destination_number" expression="^predictive_connect_([a-zA-Z0-9_-]+)$">
                    <action application="set" data="lead_id=$1"/>
                    <action application="log" data="INFO [DIALER] Predictive connect: ${lead_id}"/>
                    <action application="fifo" data="dialer_${campaign_id}@${tenant_id} out nowait"/>
                  </condition>
                </extension>
                
            """, config.getPbxCoreUrl());
    }
    
    private String buildAiExtensions(TelecomStackConfig config) {
        if (!config.isEnableAiTranscription() && !config.isEnableAiSentiment() && !config.isEnableAiAgentAssist()) {
            return "<!-- AI features disabled -->\n";
        }
        
        return String.format("""
            
                <!-- ============================================================ -->
                <!-- AI FEATURES                                                  -->
                <!-- ============================================================ -->
                
                <!-- Start AI audio fork mid-call -->
                <extension name="ai_fork_start">
                  <condition field="destination_number" expression="^ai_start$">
                    <action application="audio_fork" data="start %s/stream?uuid=${uuid}&amp;tenant=${tenant_id}&amp;features=%s"/>
                    <action application="log" data="INFO [AI] Audio fork started: ${uuid}"/>
                  </condition>
                </extension>
                
                <!-- Stop AI audio fork -->
                <extension name="ai_fork_stop">
                  <condition field="destination_number" expression="^ai_stop$">
                    <action application="audio_fork" data="stop"/>
                    <action application="log" data="INFO [AI] Audio fork stopped: ${uuid}"/>
                  </condition>
                </extension>
                
                <!-- PCI pause (stop recording/transcription for sensitive data) -->
                <extension name="ai_pci_pause">
                  <condition field="destination_number" expression="^pci_pause$">
                    <action application="audio_fork" data="pause"/>
                    <action application="record_session" data="pause"/>
                    <action application="log" data="INFO [PCI] Recording paused: ${uuid}"/>
                  </condition>
                </extension>
                
                <extension name="ai_pci_resume">
                  <condition field="destination_number" expression="^pci_resume$">
                    <action application="audio_fork" data="resume"/>
                    <action application="record_session" data="resume"/>
                    <action application="log" data="INFO [PCI] Recording resumed: ${uuid}"/>
                  </condition>
                </extension>
                
                <!-- AI Bot conversation -->
                <extension name="ai_bot">
                  <condition field="destination_number" expression="^ai_bot_([a-zA-Z0-9_-]+)$">
                    <action application="answer"/>
                    <action application="set" data="bot_id=$1"/>
                    <action application="audio_fork" data="start %s/bot?tenant=${tenant_id}&amp;bot=${bot_id}&amp;uuid=${uuid}"/>
                    <action application="playback" data="silence_stream://300000,1400"/>
                  </condition>
                </extension>
                
            """, 
            config.getAiServiceUrl(),
            config.getAiFeatureFlags(),
            config.getAiServiceUrl()
        );
    }
    
    private String buildUtilityExtensions(TelecomStackConfig config) {
        return String.format("""
            
                <!-- ============================================================ -->
                <!-- UTILITY EXTENSIONS                                           -->
                <!-- ============================================================ -->
                
                <!-- Park -->
                <extension name="park">
                  <condition field="destination_number" expression="^park$">
                    <action application="set" data="fifo_music=$${hold_music}"/>
                    <action application="fifo" data="park@${tenant_id} in"/>
                  </condition>
                </extension>
                
                <extension name="unpark">
                  <condition field="destination_number" expression="^unpark_([a-zA-Z0-9_-]+)$">
                    <action application="fifo" data="park@${tenant_id} out nowait $1"/>
                  </condition>
                </extension>
                
                <!-- Hold music -->
                <extension name="moh">
                  <condition field="destination_number" expression="^moh$">
                    <action application="answer"/>
                    <action application="endless_playback" data="local_stream://moh"/>
                  </condition>
                </extension>
                
                <!-- DTMF detection for mid-call actions -->
                <extension name="dtmf_handler">
                  <condition field="destination_number" expression="^dtmf$">
                    <action application="bind_digit_action" data="my_digits,*1,exec:transfer,spy_${uuid} XML default"/>
                    <action application="bind_digit_action" data="my_digits,*2,exec:transfer,whisper_${uuid} XML default"/>
                    <action application="bind_digit_action" data="my_digits,*3,exec:transfer,barge_${uuid} XML default"/>
                    <action application="bind_digit_action" data="my_digits,##,exec:transfer,pci_pause XML default"/>
                    <action application="bind_digit_action" data="my_digits,**,exec:transfer,pci_resume XML default"/>
                    <action application="digit_action_set_realm" data="my_digits"/>
                  </condition>
                </extension>
                
                <!-- Health check -->
                <extension name="health">
                  <condition field="destination_number" expression="^health$">
                    <action application="answer"/>
                    <action application="playback" data="silence_stream://200"/>
                    <action application="hangup" data="NORMAL_CLEARING"/>
                  </condition>
                </extension>
                
                <!-- Echo test -->
                <extension name="echo">
                  <condition field="destination_number" expression="^9196$">
                    <action application="answer"/>
                    <action application="echo"/>
                  </condition>
                </extension>
                
                <!-- Playback announcement -->
                <extension name="announcement">
                  <condition field="destination_number" expression="^announce_(.+)$">
                    <action application="answer"/>
                    <action application="playback" data="$1"/>
                    <action application="hangup"/>
                  </condition>
                </extension>
                
            """);
    }
    
    // ====================================================================
    // ADDITIONAL CONFIG FILES
    // ====================================================================
    
    public String buildConferenceConfig(Tenant tenant, TelecomStackConfig config) {
        if (!config.isEnableConference()) {
            return "<!-- Conference disabled -->";
        }
        
        return String.format("""
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration name="conference.conf" description="Conference Configuration">
              <advertise>
                <room name="default" status="Available"/>
              </advertise>
              
              <profiles>
                <profile name="%s-conf">
                  <param name="domain" value="%s"/>
                  <param name="rate" value="48000"/>
                  <param name="interval" value="20"/>
                  <param name="energy-level" value="100"/>
                  <param name="max-members" value="%d"/>
                  <param name="sound-prefix" value="$${sounds_dir}/en/us/callie"/>
                  <param name="muted-sound" value="conference/conf-muted.wav"/>
                  <param name="unmuted-sound" value="conference/conf-unmuted.wav"/>
                  <param name="alone-sound" value="conference/conf-alone.wav"/>
                  <param name="enter-sound" value="tone_stream://%%%(200,0,500,600,700)"/>
                  <param name="exit-sound" value="tone_stream://%%%(500,0,300,200,100,50,25)"/>
                  <param name="kicked-sound" value="conference/conf-kicked.wav"/>
                  <param name="locked-sound" value="conference/conf-locked.wav"/>
                  <param name="is-locked-sound" value="conference/conf-is-locked.wav"/>
                  <param name="is-unlocked-sound" value="conference/conf-is-unlocked.wav"/>
                  <param name="pin-sound" value="conference/conf-pin.wav"/>
                  <param name="bad-pin-sound" value="conference/conf-bad-pin.wav"/>
                  <param name="caller-id-name" value="$${outbound_caller_name}"/>
                  <param name="caller-id-number" value="$${outbound_caller_id}"/>
                  <param name="comfort-noise" value="true"/>
                  <param name="moh-sound" value="$${hold_music}"/>
                  %s
                  %s
                  <param name="video-mode" value="passthrough"/>
                  <param name="conference-flags" value="rfc-4579|livearray-sync"/>
                </profile>
              </profiles>
            </configuration>
            """,
            config.getTenantSlug(),
            config.getFreeSwitchInternalDomain(),
            config.getMaxConferenceParticipants(),
            config.isConferenceModerator() ? "<param name=\"moderator-controls\" value=\"true\"/>" : "",
            config.isConferenceRecording() ? 
                "<param name=\"auto-record\" value=\"" + config.getRecordingPath() + "/conf/${conference_name}_${strftime(%Y%m%d-%H%M%S)}.wav\"/>" : ""
        );
    }
    
    public String buildFifoConfig(Tenant tenant, TelecomStackConfig config) {
        return String.format("""
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration name="fifo.conf" description="FIFO Configuration">
              <settings>
                <param name="delete-all-outbound-member-on-startup" value="false"/>
              </settings>
              <fifos>
                <fifo name="default@%s" importance="0">
                  <member timeout="60" simo="1" lag="20">{fifo_member_wait=nowait}user/1000</member>
                </fifo>
                <fifo name="park@%s" importance="0"/>
                <fifo name="sales@%s" importance="5"/>
                <fifo name="support@%s" importance="5"/>
                <fifo name="general@%s" importance="0"/>
              </fifos>
            </configuration>
            """,
            config.getTenantId(),
            config.getTenantId(),
            config.getTenantId(),
            config.getTenantId(),
            config.getTenantId()
        );
    }
    
    public String buildSofiaConfig(Tenant tenant, TelecomStackConfig config) {
        return String.format("""
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration name="sofia.conf" description="sofia SIP">
              <global_settings>
                <param name="log-level" value="0"/>
                <param name="debug-presence" value="0"/>
              </global_settings>
              <profiles>
                <profile name="internal">
                  <settings>
                    <param name="context" value="public"/>
                    <param name="sip-port" value="%d"/>
                    <param name="sip-ip" value="$${local_ip_v4}"/>
                    <param name="rtp-ip" value="$${local_ip_v4}"/>
                    <param name="codec-prefs" value="OPUS,PCMU,PCMA,G722,G729"/>
                    <param name="inbound-codec-negotiation" value="generous"/>
                    <param name="auth-calls" value="true"/>
                    <param name="ext-sip-ip" value="auto-nat"/>
                    <param name="ext-rtp-ip" value="auto-nat"/>
                    <param name="hold-music" value="$${hold_music}"/>
                    <param name="session-timeout" value="1800"/>
                    <param name="enable-timer" value="false"/>
                    <param name="minimum-session-expires" value="120"/>
                  </settings>
                </profile>
                <profile name="external">
                  <gateways>
                    <gateway name="kamailio">
                      <param name="username" value="freeswitch"/>
                      <param name="realm" value="%s"/>
                      <param name="proxy" value="kamailio:5060"/>
                      <param name="register" value="false"/>
                      <param name="context" value="public"/>
                    </gateway>
                  </gateways>
                  <settings>
                    <param name="context" value="public"/>
                    <param name="sip-port" value="5080"/>
                    <param name="sip-ip" value="$${local_ip_v4}"/>
                    <param name="rtp-ip" value="$${local_ip_v4}"/>
                    <param name="codec-prefs" value="OPUS,PCMU,PCMA,G722,G729"/>
                    <param name="auth-calls" value="false"/>
                    <param name="ext-sip-ip" value="auto-nat"/>
                    <param name="ext-rtp-ip" value="auto-nat"/>
                  </settings>
                </profile>
              </profiles>
            </configuration>
            """,
            config.getSipUdpPort(),
            config.getRealm()
        );
    }
    
    public String buildEventSocketConfig(Tenant tenant, TelecomStackConfig config) {
        return String.format("""
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration name="event_socket.conf" description="Event Socket">
              <settings>
                <param name="nat-map" value="false"/>
                <param name="listen-ip" value="0.0.0.0"/>
                <param name="listen-port" value="%d"/>
                <param name="password" value="%s"/>
                <param name="apply-inbound-acl" value="lan,localnet.auto"/>
              </settings>
            </configuration>
            """,
            config.getEslPort(),
            config.getEslPassword() != null ? config.getEslPassword() : "esl_" + tenant.getId().toString().substring(0, 8) + "_secure"
        );
    }
    
    public String buildVarsConfig(Tenant tenant, TelecomStackConfig config) {
        return String.format("""
            <?xml version="1.0" encoding="UTF-8"?>
            <include>
              <X-PRE-PROCESS cmd="set" data="tenant_id=%s"/>
              <X-PRE-PROCESS cmd="set" data="tenant_slug=%s"/>
              <X-PRE-PROCESS cmd="set" data="tenant_realm=%s"/>
              <X-PRE-PROCESS cmd="set" data="pbx_core_url=%s"/>
              <X-PRE-PROCESS cmd="set" data="ai_service_url=%s"/>
              <X-PRE-PROCESS cmd="set" data="recording_path=%s"/>
              <X-PRE-PROCESS cmd="set" data="hold_music=local_stream://moh"/>
              <X-PRE-PROCESS cmd="set" data="default_language=%s"/>
              <X-PRE-PROCESS cmd="set" data="ai_features=%s"/>
              <X-PRE-PROCESS cmd="set" data="supervisor_features=%s"/>
            </include>
            """,
            config.getTenantId(),
            config.getTenantSlug(),
            config.getRealm(),
            config.getPbxCoreUrl(),
            config.getAiServiceUrl() != null ? config.getAiServiceUrl() : "",
            config.getRecordingPath(),
            config.getIvrDefaultLanguage() != null ? config.getIvrDefaultLanguage() : "en-US",
            config.getAiFeatureFlags(),
            config.getSupervisorFeatureFlags()
        );
    }

    public String buildIvrHandlerLua(Tenant tenant, TelecomStackConfig config) {
        log.info("Building IVR handler Lua script for tenant {}", tenant.getSlug());

        return String.format("""
-- ============================================================
-- ivr_handler.lua
-- Dynamic IVR handler for Dalai LLAMA AI-Native PBX
-- Tenant: %s
-- Generated: %s
-- ============================================================

local node_id = argv[1]

local tenant_id = session:getVariable("tenant_id") or "%s"
local caller_id = session:getVariable("caller_id_number") or "anonymous"
local call_uuid = session:getVariable("uuid")
local pbx_core_url = "%s"

local function log(level, message)
    freeswitch.consoleLog(level, "[IVR:" .. tenant_id .. ":" .. node_id .. "] " .. message .. "\\n")
end

local function http_get(url)
    local cmd = string.format("curl -s -m 5 '%%s'", url)
    local handle = io.popen(cmd)
    if not handle then
        log("ERROR", "Failed to execute curl")
        return nil
    end
    local result = handle:read("*a")
    handle:close()
    return result
end

local function http_post(url, json_data)
    local cmd = string.format("curl -s -m 5 -X POST -H 'Content-Type: application/json' -d '%%s' '%%s'", 
                              json_data:gsub("'", "'\\\\''"), url)
    local handle = io.popen(cmd)
    if not handle then
        log("ERROR", "Failed to execute curl POST")
        return nil
    end
    local result = handle:read("*a")
    handle:close()
    return result
end

local function parse_ivr_config(json_str)
    if not json_str or json_str == "" then
        return nil
    end
    
    local config = {}
    config.nodeId = json_str:match('"nodeId"%%s*:%%s*"([^"]*)"')
    config.type = json_str:match('"type"%%s*:%%s*"([^"]*)"')
    config.prompt = json_str:match('"prompt"%%s*:%%s*"([^"]*)"')
    config.promptFile = json_str:match('"promptFile"%%s*:%%s*"([^"]*)"')
    config.timeout = tonumber(json_str:match('"timeout"%%s*:%%s*(%%d+)')) or 5
    config.maxDigits = tonumber(json_str:match('"maxDigits"%%s*:%%s*(%%d+)')) or 1
    config.minDigits = tonumber(json_str:match('"minDigits"%%s*:%%s*(%%d+)')) or 1
    config.maxRetries = tonumber(json_str:match('"maxRetries"%%s*:%%s*(%%d+)')) or 3
    config.invalidPrompt = json_str:match('"invalidPrompt"%%s*:%%s*"([^"]*)"')
    config.transferTarget = json_str:match('"transferTarget"%%s*:%%s*"([^"]*)"')
    config.queueId = json_str:match('"queueId"%%s*:%%s*"([^"]*)"')
    config.botId = json_str:match('"botId"%%s*:%%s*"([^"]*)"')
    config.isFinal = json_str:match('"isFinal"%%s*:%%s*true') ~= nil
    
    config.dtmfMappings = {}
    for key, target in json_str:gmatch('"(%%d)"%%s*:%%s*"([^"]*)"') do
        config.dtmfMappings[key] = target
    end
    
    return config
end

local function play_prompt(prompt_file, tts_text)
    if prompt_file and prompt_file ~= "" then
        session:streamFile(prompt_file)
    elseif tts_text and tts_text ~= "" then
        session:execute("speak", "flite|kal|" .. tts_text)
    else
        session:streamFile("silence_stream://500")
    end
end

local function collect_digits(config)
    local digits = ""
    local attempts = 0
    
    while attempts < config.maxRetries do
        play_prompt(config.promptFile, config.prompt)
        
        digits = session:playAndGetDigits(
            config.minDigits,
            config.maxDigits,
            config.maxRetries,
            config.timeout * 1000,
            "#",
            "",
            config.invalidPrompt or "ivr/ivr-that_was_an_invalid_entry.wav",
            "ivr_input",
            "\\\\d+"
        )
        
        if digits and digits ~= "" then
            return digits
        end
        
        attempts = attempts + 1
        log("INFO", "No input received, attempt " .. attempts)
    end
    
    return nil
end

local function execute_ai_bot(config)
    log("INFO", "Starting AI bot: " .. (config.botId or "default"))
    
    local ai_service_url = "%s"
    local fork_url = ai_service_url .. "/bot?tenant=" .. tenant_id 
                     .. "&bot=" .. (config.botId or "default")
                     .. "&uuid=" .. call_uuid
                     .. "&caller=" .. caller_id
    
    session:execute("audio_fork", "start " .. fork_url)
    session:execute("playback", "silence_stream://60000,1400")
    session:execute("audio_fork", "stop")
end

local function execute_ivr()
    log("INFO", "Starting IVR node: " .. node_id .. " for caller: " .. caller_id)
    
    local config_url = pbx_core_url .. "/v1/ivr/" .. tenant_id .. "/nodes/" .. node_id
    log("DEBUG", "Fetching config from: " .. config_url)
    
    local config_json = http_get(config_url)
    if not config_json then
        log("ERROR", "Failed to fetch IVR config")
        session:hangup("NORMAL_TEMPORARY_FAILURE")
        return
    end
    
    local config = parse_ivr_config(config_json)
    if not config then
        log("ERROR", "Failed to parse IVR config")
        session:hangup("NORMAL_TEMPORARY_FAILURE")
        return
    end
    
    log("INFO", "IVR type: " .. (config.type or "menu"))
    
    local event_data = string.format(
        '{"tenantId":"%%s","callId":"%%s","nodeId":"%%s","caller":"%%s","event":"ivr_enter"}',
        tenant_id, call_uuid, node_id, caller_id
    )
    http_post(pbx_core_url .. "/v1/events/ivr", event_data)
    
    local ivr_type = config.type or "menu"
    
    if ivr_type == "menu" then
        local digits = collect_digits(config)
        
        if digits then
            log("INFO", "DTMF input: " .. digits)
            local target = config.dtmfMappings[digits]
            
            if target then
                if target:match("^ivr_") or target:match("^queue_") or target:match("^agent_") then
                    session:execute("transfer", target .. " XML default")
                else
                    session:execute("transfer", target .. " XML default")
                end
            else
                log("WARN", "No mapping for digit: " .. digits)
                session:execute("transfer", "ivr_" .. node_id .. " XML default")
            end
        else
            log("WARN", "No valid input after retries")
            if config.transferTarget then
                session:execute("transfer", config.transferTarget .. " XML default")
            else
                session:hangup("NO_USER_RESPONSE")
            end
        end
        
    elseif ivr_type == "announcement" then
        play_prompt(config.promptFile, config.prompt)
        
        if config.transferTarget then
            session:execute("transfer", config.transferTarget .. " XML default")
        elseif config.isFinal then
            session:hangup("NORMAL_CLEARING")
        end
        
    elseif ivr_type == "input" then
        local input = collect_digits(config)
        
        if input then
            local process_data = string.format(
                '{"tenantId":"%%s","callId":"%%s","nodeId":"%%s","input":"%%s"}',
                tenant_id, call_uuid, node_id, input
            )
            local result = http_post(pbx_core_url .. "/v1/ivr/" .. tenant_id .. "/process", process_data)
            
            local next_node = result:match('"nextNode"%%s*:%%s*"([^"]*)"')
            if next_node then
                session:execute("transfer", next_node .. " XML default")
            elseif config.transferTarget then
                session:execute("transfer", config.transferTarget .. " XML default")
            end
        end
        
    elseif ivr_type == "queue" then
        local queue_id = config.queueId or "default"
        log("INFO", "Transferring to queue: " .. queue_id)
        session:execute("transfer", "queue_" .. queue_id .. " XML default")
        
    elseif ivr_type == "agent" then
        local agent = config.transferTarget
        if agent then
            log("INFO", "Transferring to agent: " .. agent)
            session:execute("transfer", "agent_" .. agent .. " XML default")
        else
            log("ERROR", "No agent specified for direct transfer")
            session:hangup("NO_ROUTE_DESTINATION")
        end
        
    elseif ivr_type == "ai_bot" then
        execute_ai_bot(config)
        
        local ai_result = session:getVariable("ai_transfer_target")
        if ai_result and ai_result ~= "" then
            session:execute("transfer", ai_result .. " XML default")
        elseif config.transferTarget then
            session:execute("transfer", config.transferTarget .. " XML default")
        end
        
    elseif ivr_type == "callback" then
        play_prompt(config.promptFile, config.prompt or "Please enter your callback number followed by pound")
        
        local callback_number = collect_digits({
            minDigits = 10,
            maxDigits = 15,
            timeout = 10,
            maxRetries = 3,
            invalidPrompt = "ivr/ivr-that_was_an_invalid_entry.wav"
        })
        
        if callback_number then
            local cb_data = string.format(
                '{"tenantId":"%%s","callId":"%%s","callerNumber":"%%s","callbackNumber":"%%s","queueId":"%%s"}',
                tenant_id, call_uuid, caller_id, callback_number, config.queueId or "default"
            )
            http_post(pbx_core_url .. "/v1/callbacks", cb_data)
            
            session:execute("playback", "ivr/ivr-call_back_shortly.wav")
            session:hangup("NORMAL_CLEARING")
        end
        
    elseif ivr_type == "voicemail" then
        local vm_box = config.transferTarget or "general"
        session:execute("transfer", "vm_" .. vm_box .. " XML default")
        
    elseif ivr_type == "transfer_external" then
        local target = config.transferTarget
        if target then
            log("INFO", "External transfer to: " .. target)
            session:execute("bridge", "sofia/external/" .. target .. "@kamailio:5060")
        end
        
    else
        log("WARN", "Unknown IVR type: " .. ivr_type)
        session:hangup("NORMAL_UNSPECIFIED")
    end
end

local status, err = pcall(execute_ivr)
if not status then
    log("ERROR", "IVR execution failed: " .. tostring(err))
    session:hangup("NORMAL_TEMPORARY_FAILURE")
end

log("INFO", "IVR node completed: " .. node_id)
""",
                tenant.getSlug(),
                java.time.Instant.now(),
                config.getTenantId(),
                config.getPbxCoreUrl(),
                config.getAiServiceUrl() != null ? config.getAiServiceUrl() : "ws://ai-service:9000"
        );
    }
}
