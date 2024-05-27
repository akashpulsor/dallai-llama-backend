package com.dalaillama.content.repository;

import com.dalaillama.content.entity.PublishDestination;
import com.dalaillama.content.entity.SearchData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

@Component
public interface PublishDestinationRepository  extends JpaRepository<PublishDestination, Integer> {
}
