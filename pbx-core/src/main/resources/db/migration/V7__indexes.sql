-- V7__indexes.sql

-- Kamailio tables
CREATE INDEX idx_subscriber_tenant ON subscriber(tenant_id);
CREATE INDEX idx_subscriber_domain_active ON subscriber(domain, is_active);
CREATE INDEX idx_domain_tenant ON domain(tenant_id);
CREATE INDEX idx_dialplan_dpid ON dialplan(dpid);
CREATE INDEX idx_dialplan_tenant ON dialplan(tenant_id);
CREATE INDEX idx_location_username ON location(username, domain);
CREATE INDEX idx_location_ruid ON location(ruid);
CREATE INDEX idx_uacreg_tenant ON uacreg(tenant_id);

-- PBX-Core tables
CREATE INDEX idx_agents_tenant ON agents(tenant_id);
CREATE INDEX idx_agents_status ON agents(tenant_id, status);
CREATE INDEX idx_agents_extension ON agents(tenant_id, extension);
CREATE INDEX idx_queues_tenant ON queues(tenant_id);
CREATE INDEX idx_routing_tenant_active ON routing_policies(tenant_id, is_active);
CREATE INDEX idx_routing_match ON routing_policies(tenant_id, match_type, match_value);
CREATE INDEX idx_call_records_tenant_time ON call_records(tenant_id, created_at DESC);
CREATE INDEX idx_call_records_call_id ON call_records(call_id);
CREATE INDEX idx_call_records_agent ON call_records(agent_id, created_at DESC);
CREATE INDEX idx_sip_trunks_tenant ON sip_trunks(tenant_id);
CREATE INDEX idx_tenant_dialplan_context ON tenant_dialplan(context);
CREATE INDEX idx_ivr_flows_tenant ON ivr_flows(tenant_id);