package com.hechang.insighthub.model.dto.task;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RevisePlanRequest(@Min(1) int expectedRevision, @NotBlank @Size(max = 2000) String revision) {}
