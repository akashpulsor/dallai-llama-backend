
package com.dalai.llama.blobmanager.repository;

import com.dalai.llama.blobmanager.model.CloudConfig;
import org.springframework.data.jpa.repository.JpaRepository;
//import org.springframework.data.jpa.repository.JpaRepository;

public interface CloudConfigRepository  extends JpaRepository<CloudConfig, String> {
}
