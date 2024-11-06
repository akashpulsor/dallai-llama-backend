package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.ERole;
import org.springframework.boot.crm.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository   extends JpaRepository<Role, Long> {

    Optional<Role> findByName(ERole name);
}
