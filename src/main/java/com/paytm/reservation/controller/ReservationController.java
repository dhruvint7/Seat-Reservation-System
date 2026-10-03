package com.paytm.reservation.controller;

import com.paytm.reservation.dto.ReserveRequest;
import com.paytm.reservation.dto.ReserveResponse;
import com.paytm.reservation.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/shows/{showId}/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public ReserveResponse reserve(
            @PathVariable Long showId,
            @Valid @RequestBody ReserveRequest request,
            Authentication authentication
    ) {
        return reservationService.reserve(
                showId,
                authentication.getName(),
                request
        );
    }
    @PostMapping("/reservations/{reservationId}/cancel")
    public ReserveResponse cancel(
            @PathVariable Long reservationId,
            Authentication authentication
    ) {
        return reservationService.cancel(
                reservationId,
                authentication.getName()
        );
    }
}