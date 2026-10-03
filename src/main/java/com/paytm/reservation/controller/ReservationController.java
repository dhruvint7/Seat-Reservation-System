package com.paytm.reservation.controller;

import com.paytm.reservation.dto.ReserveRequest;
import com.paytm.reservation.dto.ReserveResponse;
import com.paytm.reservation.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shows")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/{showId}/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public ReserveResponse reserve(
            @PathVariable Long showId,
            @Valid @RequestBody ReserveRequest request
    ) {
        return reservationService.reserve(showId, "test-user", request);
    }
}