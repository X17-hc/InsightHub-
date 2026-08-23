package com.hechang.insighthub.model.enums;

/** Research-result quality is independent from execution completion. */
public enum QualityStatus {
    PENDING,
    PASS,
    FAIL,
    NOT_EVALUATED,
    LEGACY_SYNTHETIC;

    public static QualityStatus fromVerdict(String value) {
        if ("PASS".equalsIgnoreCase(value)) return PASS;
        if ("FAIL".equalsIgnoreCase(value)) return FAIL;
        return NOT_EVALUATED;
    }
}
