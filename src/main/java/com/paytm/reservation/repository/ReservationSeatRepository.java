package com.paytm.reservation.repository;

import com.paytm.reservation.model.ReservationSeat;
import com.paytm.reservation.model.ReservationSeatId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationSeatRepository
        extends JpaRepository<ReservationSeat, ReservationSeatId> {
}