package com.paytm.reservation.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "reservation_seats")
@IdClass(ReservationSeatId.class)
public class ReservationSeat {

    @Id
    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Id
    @Column(name = "seat_number", nullable = false, length = 50)
    private String seatNumber;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    public ReservationSeat(Long reservationId, Long showId, String seatNumber) {
        this.reservationId = reservationId;
        this.showId = showId;
        this.seatNumber = seatNumber;
    }
}