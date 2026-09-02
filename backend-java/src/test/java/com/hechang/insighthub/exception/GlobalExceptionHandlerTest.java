package com.hechang.insighthub.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessErrorCodeMapsToMatchingHttpStatus() {
        assertStatus(ErrorCode.PARAMS_ERROR, HttpStatus.BAD_REQUEST);
        assertStatus(ErrorCode.NOT_LOGIN_ERROR, HttpStatus.UNAUTHORIZED);
        assertStatus(ErrorCode.NO_AUTH_ERROR, HttpStatus.UNAUTHORIZED);
        assertStatus(ErrorCode.FORBIDDEN_ERROR, HttpStatus.FORBIDDEN);
        assertStatus(ErrorCode.NOT_FOUND_ERROR, HttpStatus.NOT_FOUND);
        assertStatus(ErrorCode.CONFLICT_ERROR, HttpStatus.CONFLICT);
        assertStatus(ErrorCode.TOO_MANY_REQUEST, HttpStatus.TOO_MANY_REQUESTS);
        assertStatus(ErrorCode.OPERATION_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private void assertStatus(ErrorCode errorCode, HttpStatus expected) {
        var response = handler.handleBusiness(new BusinessException(errorCode));

        assertEquals(expected, response.getStatusCode());
        assertEquals(errorCode.getCode(), response.getBody().getCode());
    }
}
