package org.springframework.boot.crm.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GetMetaDataTool implements Tool{
    @Override
    public String getName() {
        return "get_metadata";
    }

    @Override
    public String getDescription() {
        return "To get metadata of call use this function";
    }

    @Override
    public Map<String, Object> getFunctionDefinition() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> query = new HashMap<>();
        query.put("type", "string");
        query.put("description", "The query to get metadata about the ongoing calls");
        properties.put("query", query);

        parameters.put("properties", properties);
        parameters.put("required", Arrays.asList("query"));
        return parameters;

    }

    @Override
    public String functionImplementation(Object... args) {
        return "";
    }


}
