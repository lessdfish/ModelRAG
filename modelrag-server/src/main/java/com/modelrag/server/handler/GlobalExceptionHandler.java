package com.modelrag.server.handler;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.exception.ModelRagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.client.ResourceAccessException;
import software.amazon.awssdk.core.exception.SdkClientException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ModelRagException.class)
    ResponseEntity<ApiResponse<Void>> domain(ModelRagException e) {
        HttpStatus status = switch (e.errorCode()) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_DOCUMENT, DUPLICATE_OPERATION -> HttpStatus.CONFLICT;
            case LOW_CONFIDENCE, OCR_REQUIRED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(e.errorCode().code, e.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiResponse<Void>> database(DataAccessException e) {
        log.error("Database dependency unavailable", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(ErrorCode.DEPENDENCY_UNAVAILABLE.code, "数据库暂不可用"));
    }

    @ExceptionHandler({RedisConnectionFailureException.class, ResourceAccessException.class,
            SdkClientException.class, java.net.ConnectException.class, java.net.http.HttpConnectTimeoutException.class})
    ResponseEntity<ApiResponse<Void>> dependency(Exception e) {
        log.error("External dependency unavailable: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(ErrorCode.DEPENDENCY_UNAVAILABLE.code, "外部依赖暂不可用"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> validation(IllegalArgumentException e) {
        return ApiResponse.fail(40001, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> other(Exception e) {
        log.error("Unhandled request error", e);
        return ApiResponse.fail(50000, "内部错误");
    }
}
