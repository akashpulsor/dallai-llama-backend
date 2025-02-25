package org.springframework.boot.crm.service;

import java.io.IOException;
import java.util.Map;

public interface Tool {

    String getName();

    String getDescription();

    Map<String, Object> getFunctionDefinition();

    String functionImplementation(Object... args) throws IOException;
}
