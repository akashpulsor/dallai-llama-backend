package org.springframework.boot.crm.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.crm.dto.IntentDTO;
import org.springframework.boot.crm.dto.PortalConfigurationDTO;
import org.springframework.boot.crm.dto.PortalConfigurationResponse;
import org.springframework.boot.crm.dto.PortalDTO;
import org.springframework.boot.crm.entity.PortalConfiguration;
import org.springframework.boot.crm.service.PortalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/portals")
@CrossOrigin(origins = "*", maxAge = 3600)
public class PortalController {

    private final PortalService portalService;

    public PortalController(PortalService portalService) {
        this.portalService = portalService;
    }




    @PostMapping("/configure")
    public ResponseEntity<PortalConfigurationResponse> createPortal(@RequestBody PortalConfigurationDTO portalConfigurationDTO) {
        return ResponseEntity.ok(portalService.createPortalConfiguration(portalConfigurationDTO));
    }

    @GetMapping("/{businessId}")
    public List<PortalConfigurationResponse> getPortal(@PathVariable int businessId) {
        return portalService.getPortalConfiguration(businessId);
    }


}
