package com.danalfintech.cryptotax.global.error;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ErrorResponse {

    private final String code;
    private final String message;
    private final int status;
    private final LocalDateTime timestamp;
    private final List<FieldError> errors;

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(),
                errorCode.getStatus().value(), LocalDateTime.now(), List.of());
    }

    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> errors) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(),
                errorCode.getStatus().value(), LocalDateTime.now(), errors);
    }

    @Getter
    @AllArgsConstructor
    public static class FieldError {
        private final String field;
        private final String value;
        private final String reason;
    }
}
