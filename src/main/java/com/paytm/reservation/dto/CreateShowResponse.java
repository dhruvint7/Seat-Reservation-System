package com.paytm.reservation.dto;

import java.util.List;

public record CreateShowResponse(
        Long showId,
        String name,
        List<SeatResponse> seats,
        Long pricePaise
) {
    public record SeatResponse(
            String seatNumber,
            String status
    ) {
    }
}