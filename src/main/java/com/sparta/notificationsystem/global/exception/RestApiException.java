package com.sparta.notificationsystem.global.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class RestApiException {
    private LocalDateTime timestamp;
    private int statusCode;
    private String errorMessage;
    private String path;
}