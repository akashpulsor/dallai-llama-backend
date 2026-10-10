package com.dalai.llama.tenant.leadmanagement.audience;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads a creator's audience CSV (RFC 4180: quoted fields, embedded commas and newlines, CRLF or
 * LF). The first row is the header; columns are recognised by name (rule 21), unknown ones ignored.
 * Each row keeps its original text verbatim for {@code lead_creator_lead_source}. */
public final class LeadCsvParser {

    public static final int MAX_ROWS = 5_000;

    public record ParsedRow(int rowNumber, String raw, List<String> emails, List<String> phones, String name, String company,
                            ShowcaseIndustry industry, String website) {
        public boolean hasContact() {
            return !emails.isEmpty() || !phones.isEmpty();
        }
    }

    private record Record(String raw, List<String> fields) {
    }

    private enum Column { EMAIL, PHONE, NAME, FIRST_NAME, LAST_NAME, COMPANY, INDUSTRY, WEBSITE, IGNORED }

    private LeadCsvParser() {
    }

    public static List<ParsedRow> parse(String csv) {
        List<Record> records = records(csv.startsWith("﻿") ? csv.substring(1) : csv);
        if (records.isEmpty()) throw new IllegalArgumentException("The file is empty");
        List<Column> columns = records.get(0).fields().stream().map(LeadCsvParser::column).toList();
        if (!columns.contains(Column.EMAIL) && !columns.contains(Column.PHONE)) {
            throw new IllegalArgumentException("The first row must name the columns, with at least an 'email' or 'phone' column");
        }
        if (records.size() - 1 > MAX_ROWS) throw new IllegalArgumentException("Upload at most " + MAX_ROWS + " rows at a time");

        List<ParsedRow> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            Record record = records.get(r);
            if (record.fields().stream().allMatch(String::isBlank)) continue;
            Set<String> emails = new LinkedHashSet<>();
            Set<String> phones = new LinkedHashSet<>();
            String name = null, first = null, last = null, company = null, website = null;
            ShowcaseIndustry industry = null;
            for (int c = 0; c < columns.size() && c < record.fields().size(); c++) {
                String value = record.fields().get(c).trim();
                if (value.isEmpty()) continue;
                switch (columns.get(c)) {
                    case EMAIL -> splitInto(value, "[;|\\s]+", emails);
                    case PHONE -> splitInto(value, "[;|/]+", phones);
                    case NAME -> name = value;
                    case FIRST_NAME -> first = value;
                    case LAST_NAME -> last = value;
                    case COMPANY -> company = value;
                    case INDUSTRY -> industry = industry(value);
                    case WEBSITE -> website = value;
                    default -> { }
                }
            }
            if (name == null && (first != null || last != null)) name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
            rows.add(new ParsedRow(r + 1, record.raw(), List.copyOf(emails), List.copyOf(phones), cap(name, 120), cap(company, 160),
                    industry, website != null && website.startsWith("http") ? cap(website, 255) : null));
        }
        return rows;
    }

    private static Column column(String header) {
        String h = header.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
        if (h.matches("e?mail\\d*|emails|emailaddress(es)?\\d*|workemail")) return Column.EMAIL;
        if (h.matches("(phone|mobile|tel|telephone|whatsapp)(number)?\\d*|phones")) return Column.PHONE;
        if (h.matches("name|fullname|contactname|contact")) return Column.NAME;
        if (h.matches("firstname|givenname")) return Column.FIRST_NAME;
        if (h.matches("lastname|surname|familyname")) return Column.LAST_NAME;
        if (h.matches("company|companyname|organi[sz]ation|brand|business")) return Column.COMPANY;
        if (h.matches("industry|sector|category")) return Column.INDUSTRY;
        if (h.matches("website|url|site|web")) return Column.WEBSITE;
        return Column.IGNORED;
    }

    static ShowcaseIndustry industry(String raw) {
        String code = raw.trim().toUpperCase(Locale.ROOT).replace('&', '_').replaceAll("[\\s\\-]+", "_").replaceAll("_+", "_");
        for (ShowcaseIndustry i : ShowcaseIndustry.values()) if (i.name().equals(code)) return i;
        return null;
    }

    private static void splitInto(String value, String separators, Set<String> into) {
        for (String part : value.split(separators)) if (!part.isBlank()) into.add(part.trim());
    }

    private static String cap(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Splits into records, honouring quotes; keeps each record's original text. */
    private static List<Record> records(String csv) {
        List<Record> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < csv.length(); i++) {
            char ch = csv.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else field.append(ch);
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                fields.add(field.toString());
                field.setLength(0);
                records.add(new Record(csv.substring(start, i), List.copyOf(fields)));
                fields.clear();
                if (ch == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                start = i + 1;
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !fields.isEmpty()) {
            fields.add(field.toString());
            records.add(new Record(csv.substring(start), List.copyOf(fields)));
        }
        return records;
    }
}
