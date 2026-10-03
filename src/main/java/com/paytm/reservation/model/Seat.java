package com.paytm.reservation.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "seats")
@IdClass(SeatId.class)
public class Seat {

    @Id
    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Id
    @Column(name = "seat_number", nullable = false, length = 50)
    private String seatNumber;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Seat() {
    }

    public Seat(Long showId, String seatNumber) {
        this.showId = showId;
        this.seatNumber = seatNumber;
        this.status = "AVAILABLE";
        this.updatedAt = LocalDateTime.now();
    }

    // getters and setters
}