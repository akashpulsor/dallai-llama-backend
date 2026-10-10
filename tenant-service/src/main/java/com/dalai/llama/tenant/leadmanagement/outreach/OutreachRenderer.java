package com.dalai.llama.tenant.leadmanagement.outreach;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fills {@code classpath:outreach/<name>.html|.txt} from a record: {@code {{field}}} is the
 * component's value (HTML-escaped in .html), {@code {{{field}}}} inserts it raw, for fragments this
 * renderer produced itself. Unknown placeholders fail loudly, so a typo never ships. */
@Component
public class OutreachRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\{?)([a-zA-Z]+)}?}}");
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public String html(String name, Record context) {
        return fill(load(name + ".html"), context, true);
    }

    public String text(String name, Record context) {
        return fill(load(name + ".txt"), context, false);
    }

    private String fill(String template, Record context, boolean html) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = valueOf(context, m.group(2));
            boolean raw = !m.group(1).isEmpty();
            m.appendReplacement(out, Matcher.quoteReplacement(html && !raw ? escape(value) : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String valueOf(Record context, String field) {
        for (RecordComponent component : context.getClass().getRecordComponents()) {
            if (component.getName().equals(field)) {
                try {
                    Object value = component.getAccessor().invoke(context);
                    return value == null ? "" : value.toString();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("Template field '" + field + "' not in " + context.getClass().getSimpleName());
    }

    private String load(String file) {
        return cache.computeIfAbsent(file, f -> {
            try {
                return new String(new ClassPathResource("outreach/" + f).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Missing outreach template " + f, e);
            }
        });
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
