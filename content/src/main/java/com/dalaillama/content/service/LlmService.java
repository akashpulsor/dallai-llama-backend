package com.dalaillama.content.service;

import com.dalaillama.content.entity.Llm;
import com.dalaillama.content.entity.Tools;

import java.util.List;

public interface LlmService {

    Llm addLlm(Llm llm);

    List<Llm> getAllLlm();
}
