package com.hechang.insighthub.model.dto.task;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ApprovePlanRequest(@Min(1) int expectedRevision, @Size(max = 500) String remark) {}
