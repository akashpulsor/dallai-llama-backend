package com.dalai.llama.videogen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VideoGenerationApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoGenerationApplication.class, args);
    }
}
