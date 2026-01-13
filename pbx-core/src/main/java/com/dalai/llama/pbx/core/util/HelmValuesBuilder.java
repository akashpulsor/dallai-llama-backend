package com.dalai.llama.pbx.core.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Serializes a Map -> YAML string for Helm values.yaml
 */
@Component
public class HelmValuesBuilder {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public String build(Map<String, Object> values) throws Exception {
        return yaml.writeValueAsString(values);
    }
}
