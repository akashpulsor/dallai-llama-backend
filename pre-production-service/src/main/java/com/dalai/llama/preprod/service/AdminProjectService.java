package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.dto.AdminProjectView;
import com.dalai.llama.preprod.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Ops-page reads over every project of a tenant -- operator access, not tenant-scoped auth. */
@Service
public class AdminProjectService {

    private final ProjectRepository projectRepository;

    public AdminProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Transactional(readOnly = true)
    public List<AdminProjectView> list(UUID tenantId) {
        return projectRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(project -> new AdminProjectView(project.getId(), project.getName(),
                        project.getStatus() == null ? null : project.getStatus().toString(), project.getCreatedAt()))
                .toList();
    }
}
