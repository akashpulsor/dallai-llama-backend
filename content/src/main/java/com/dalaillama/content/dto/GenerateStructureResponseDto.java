package com.dalaillama.content.dto;

import lombok.Data;

import java.util.List;

@Data
public class GenerateStructureResponseDto {

    public List<Structure> generateStructure;
    private int llamaContentId;
    @Data
    public static class Structure {
        private String heading;

        private List<String> points;

    }
}
