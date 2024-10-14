package com.sparta.notificationsystem.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<RestApiException> handleGenericException(Exception ex, HttpServletRequest request) {
        RestApiException restApiException = new RestApiException(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ex.getMessage(),
                request.getRequestURI()
                );
        return new ResponseEntity<>(
                restApiException,
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<RestApiException> illegalArgumentExceptionHandler(IllegalArgumentException ex, HttpServletRequest request) {
        RestApiException restApiException = new RestApiException(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return new ResponseEntity<>(
                restApiException,
                HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler({NoSuchElementException.class})
    public ResponseEntity<RestApiException> NoSuchElementException(NoSuchElementException ex, HttpServletRequest request) {
        RestApiException restApiException = new RestApiException(
                LocalDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return new ResponseEntity<>(
                restApiException,
                HttpStatus.NOT_FOUND
        );
    }
    @ExceptionHandler({IllegalStateException.class})
    public ResponseEntity<RestApiException> handleIllegalStateException(IllegalStateException ex, HttpServletRequest request) {
        RestApiException restApiException = new RestApiException(
                LocalDateTime.now(),   // 현재 시간 추가
                HttpStatus.CONFLICT.value(),   // 상태 코드
                ex.getMessage(),   // 에러 메시지
                request.getRequestURI()  // 요청 경로
        );
        return new ResponseEntity<>(restApiException, HttpStatus.CONFLICT);
    }

}