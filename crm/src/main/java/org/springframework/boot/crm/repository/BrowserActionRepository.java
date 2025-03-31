package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.BrowserAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BrowserActionRepository extends JpaRepository<BrowserAction, String> {

}
