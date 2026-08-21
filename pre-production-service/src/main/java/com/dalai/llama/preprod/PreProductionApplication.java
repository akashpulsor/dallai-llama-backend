package com.dalai.llama.preprod;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PreProductionApplication {

    public static void main(String[] args) {
        SpringApplication.run(PreProductionApplication.class, args);
    }
}
