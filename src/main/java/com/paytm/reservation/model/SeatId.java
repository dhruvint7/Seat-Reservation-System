package com.paytm.reservation.model;

import java.io.Serializable;
import java.util.Objects;

public class SeatId implements Serializable {

    private Long showId;
    private String seatNumber;

    public SeatId() {
    }

    public SeatId(Long showId, String seatNumber) {
        this.showId = showId;
        this.seatNumber = seatNumber;
    }

    public Long getShowId() {
        return showId;
    }

    public void setShowId(Long showId) {
        this.showId = showId;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public void setSeatNumber(String seatNumber) {
        this.seatNumber = seatNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SeatId seatId)) return false;

        return Objects.equals(showId, seatId.showId)
                && Objects.equals(seatNumber, seatId.seatNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(showId, seatNumber);
    }
}