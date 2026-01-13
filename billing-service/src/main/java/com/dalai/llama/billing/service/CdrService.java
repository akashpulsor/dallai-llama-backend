package com.dalai.llama.billing.service;

public interface CdrService {

    void processCompletedCdr(Object cdrCompletedEvent);
}
