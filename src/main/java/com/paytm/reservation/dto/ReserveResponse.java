package com.paytm.reservation.dto;

import java.util.List;

public record ReserveResponse(
        Long reservationId,
        Long showId,
        String userId,
        List<String> seats,
        Long amountPaise,
        String status
) {
}