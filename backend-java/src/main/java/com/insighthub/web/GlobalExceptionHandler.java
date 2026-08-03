package com.insighthub.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误响应，字段对齐 docs/protocol.md。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", "VALIDATION_ERROR");
        body.put("message", ex.getBindingResult().getAllErrors().isEmpty()
                ? "validation failed"
                : ex.getBindingResult().getAllErrors().get(0).getDefaultMessage());
        body.put("details", Map.of());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", "AGENT_EXECUTION_FAILED");
        body.put("message", ex.getMessage() == null ? "unexpected error" : ex.getMessage());
        body.put("details", Map.of());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
