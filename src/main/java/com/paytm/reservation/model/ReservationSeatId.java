package com.paytm.reservation.model;

import java.io.Serializable;
import java.util.Objects;

public class ReservationSeatId implements Serializable {

    private Long reservationId;
    private String seatNumber;

    public ReservationSeatId() {
    }

    public ReservationSeatId(Long reservationId, String seatNumber) {
        this.reservationId = reservationId;
        this.seatNumber = seatNumber;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public void setReservationId(Long reservationId) {
        this.reservationId = reservationId;
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
        if (!(o instanceof ReservationSeatId that)) return false;

        return Objects.equals(reservationId, that.reservationId)
                && Objects.equals(seatNumber, that.seatNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reservationId, seatNumber);
    }
}