package com.paytm.reservation.repository;
import com.paytm.reservation.model.Seat;
import com.paytm.reservation.model.SeatId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, SeatId> {
}