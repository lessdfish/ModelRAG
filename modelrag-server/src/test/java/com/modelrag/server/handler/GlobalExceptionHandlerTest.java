package com.modelrag.server.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

class GlobalExceptionHandlerTest {
    @ParameterizedTest
    @MethodSource("statuses")
    void mapsEveryDomainErrorToItsHttpContract(ErrorCode errorCode, HttpStatus expected) {
        var response = new GlobalExceptionHandler().domain(new BusinessException(errorCode, "test"));

        assertEquals(expected, response.getStatusCode());
        assertEquals(errorCode.code, response.getBody().code());
    }

    private static Stream<Arguments> statuses() {
        return Stream.of(
                Arguments.of(ErrorCode.VALIDATION, HttpStatus.BAD_REQUEST),
                Arguments.of(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
                Arguments.of(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND),
                Arguments.of(ErrorCode.DUPLICATE_DOCUMENT, HttpStatus.CONFLICT),
                Arguments.of(ErrorCode.DUPLICATE_OPERATION, HttpStatus.CONFLICT),
                Arguments.of(ErrorCode.LOW_CONFIDENCE, HttpStatus.UNPROCESSABLE_ENTITY),
                Arguments.of(ErrorCode.OCR_REQUIRED, HttpStatus.UNPROCESSABLE_ENTITY),
                Arguments.of(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS),
                Arguments.of(ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
                Arguments.of(ErrorCode.INTERNAL, HttpStatus.INTERNAL_SERVER_ERROR));
    }
}
