package com.dalaillama.content.repository;

import com.dalaillama.content.entity.ERole;
import com.dalaillama.content.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;


@Repository
public interface RoleRepository  extends JpaRepository<Role, Long> {

    Optional<Role> findByName(ERole name);
}
