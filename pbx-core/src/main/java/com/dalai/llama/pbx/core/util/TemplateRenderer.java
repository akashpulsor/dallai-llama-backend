package com.dalai.llama.pbx.core.util;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

@Component
public class TemplateRenderer {

    public String render(String template, Map<String, String> vars) {
        String out = template;
        if (vars != null) {
            for (var e : vars.entrySet()) {
                out = out.replace("${" + e.getKey() + "}", e.getValue());
            }
        }
        return out;
    }

    public String renderTemplateFile(String classpathFile, Map<String, String> vars) {
        try {
            ClassPathResource res = new ClassPathResource(classpathFile);
            try (InputStream is = res.getInputStream()) {
                String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return render(template, vars);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void writeFile(String path, String content) {
        try {
            Path p = Paths.get(path);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
