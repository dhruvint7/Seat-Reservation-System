package com.paytm.reservation.dto;

import java.util.List;

public record ShowDetailsResponse(
        Long showId,
        String name,
        Long pricePaise,
        List<SeatStatusResponse> seats,
        int total,
        int available,
        int held,
        int confirmed
) {
    public record SeatStatusResponse(
            String seatNumber,
            String status
    ) {}
}