package com.dalai.llama.product.service.impl;


import com.dalai.llama.product.domain.entity.Did;
import com.dalai.llama.product.domain.entity.SipEndpoint;
import com.dalai.llama.product.domain.exception.SipEndpointConflictException;
import com.dalai.llama.product.repository.SipEndpointRepository;
import com.dalai.llama.product.service.SipEndpointService;
import com.dalai.llama.product.util.Ha1HashGenerator;
import com.dalai.llama.product.util.SipCredentialGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SipEndpointServiceImpl implements SipEndpointService {

    private final SipEndpointRepository sipEndpointRepository;

    @Value("${sip.domain:sip.dalaillama.in}")
    private String sipDomain;

    @Value("${sip.realm:dalaillama.in}")
    private String sipRealm;

    @Override
    public SipEndpoint createEndpoint(Did did) {
        // Generate username from DID number: did_919876543210
        String username = "did_" + did.getNumber().replace("+", "");

        // Check for existing endpoint
        if (sipEndpointRepository.findByUsernameAndDomain(username, sipDomain).isPresent()) {
            throw new SipEndpointConflictException("SIP endpoint already exists: " + username);
        }

        // Generate secure password and HA1 hash
        String password = SipCredentialGenerator.generatePassword(24);
        String ha1Hash = Ha1HashGenerator.generate(username, sipRealm, password);

        SipEndpoint endpoint = SipEndpoint.builder()
                .id(UUID.randomUUID())
                .did(did)
                .username(username)
                .passwordHash(ha1Hash)
                .domain(sipDomain)
                .realm(sipRealm)
                .active(true)
                .registeredInKamailio(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        endpoint = sipEndpointRepository.save(endpoint);
        log.info("Created SIP endpoint for DID {}: {}", did.getNumber(), username);

        return endpoint;
    }

    public SipEndpoint getByDidId(UUID didId) {
        return sipEndpointRepository.findByDid_Id(didId).orElse(null);
    }

    @Override
    public SipEndpoint getById(UUID id) {
        return sipEndpointRepository.findById(id).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        sipEndpointRepository.deleteById(id);
    }

    public void markAsSynced(UUID endpointId) {
        sipEndpointRepository.findById(endpointId).ifPresent(endpoint -> {
            endpoint.setRegisteredInKamailio(true);
            endpoint.setKamailioSyncedAt(Instant.now());
            endpoint.setUpdatedAt(Instant.now());
            sipEndpointRepository.save(endpoint);
        });
    }
}