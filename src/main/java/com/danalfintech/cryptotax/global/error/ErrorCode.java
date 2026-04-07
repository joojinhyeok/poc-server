package com.danalfintech.cryptotax.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C001", "서버 내부 오류가 발생했습니다."),
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C002", "잘못된 입력값입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "C003", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "C004", "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "C005", "요청한 리소스를 찾을 수 없습니다."),

    // 인증
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "A001", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "A002", "만료된 토큰입니다."),

    // 수집
    COLLECTION_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "COL001", "이미 진행 중인 수집 작업이 있습니다."),
    COLLECTION_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "COL002", "수집 작업을 찾을 수 없습니다."),
    COLLECTION_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COL003", "수집 기능이 일시 중단되었습니다."),

    // 거래소
    EXCHANGE_API_KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "EX001", "거래소 API 키를 찾을 수 없습니다."),
    EXCHANGE_API_KEY_INVALID(HttpStatus.BAD_REQUEST, "EX002", "유효하지 않은 거래소 API 키입니다."),
    EXCHANGE_API_KEY_ALREADY_EXISTS(HttpStatus.CONFLICT, "EX003", "이미 해당 거래소의 API 키가 등록되어 있습니다."),
    EXCHANGE_API_RESPONSE_PARSE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "EX004", "거래소 API 응답 파싱에 실패했습니다.");


    private final HttpStatus status;
    private final String code;
    private final String message;
}
