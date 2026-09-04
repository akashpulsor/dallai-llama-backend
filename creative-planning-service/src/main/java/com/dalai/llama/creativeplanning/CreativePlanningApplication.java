package com.dalai.llama.creativeplanning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/** {@code @EnableAsync}: confirmed live this was a real problem without it -- {@code
 * ReferenceMaterialAnalysisService.analyzePendingForRequirement} runs one sequential vision-LLM
 * call per reference image, synchronously inside the payment-verification request/transaction,
 * which routinely exceeded the route timeout even when the payment itself succeeded (see that
 * method's {@code @Async}). */
@EnableAsync
@SpringBootApplication
public class CreativePlanningApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreativePlanningApplication.class, args);
    }
}
