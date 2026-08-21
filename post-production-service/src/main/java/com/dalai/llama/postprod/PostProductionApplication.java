package com.dalai.llama.postprod;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PostProductionApplication {

    public static void main(String[] args) {
        SpringApplication.run(PostProductionApplication.class, args);
    }
}
