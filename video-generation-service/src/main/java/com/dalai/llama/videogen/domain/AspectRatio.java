package com.dalai.llama.videogen.domain;

public enum AspectRatio {
    RATIO_16_9("16:9"),
    RATIO_9_16("9:16"),
    RATIO_1_1("1:1"),
    RATIO_4_5("4:5"),
    RATIO_21_9("21:9");

    private final String wireValue;

    AspectRatio(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
