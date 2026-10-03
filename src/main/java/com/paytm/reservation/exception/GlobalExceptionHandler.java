package com.paytm.reservation.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ReservationConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleConflict(
            ReservationConflictException exception
    ) {
        return Map.of(
                "error", "RESERVATION_CONFLICT",
                "message", exception.getMessage()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(
            IllegalArgumentException exception
    ) {
        return Map.of(
                "error", "BAD_REQUEST",
                "message", exception.getMessage()
        );
    }
}