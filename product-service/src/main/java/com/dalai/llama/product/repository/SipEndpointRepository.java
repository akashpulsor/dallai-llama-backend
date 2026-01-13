package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.SipEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SipEndpointRepository extends JpaRepository<SipEndpoint, UUID> {

    Optional<SipEndpoint> findByUsernameAndDomain(String username, String domain);

    List<SipEndpoint> findByRegisteredInKamailioFalse();

    Optional<SipEndpoint> findByDid_Id(UUID didId);
}
