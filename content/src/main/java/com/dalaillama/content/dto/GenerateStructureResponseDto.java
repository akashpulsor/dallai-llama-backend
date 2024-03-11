package com.dalaillama.content.dto;

import lombok.Data;

import java.util.List;

@Data
public class GenerateStructureResponseDto {
    private String heading;

    private List<String> points;
}
