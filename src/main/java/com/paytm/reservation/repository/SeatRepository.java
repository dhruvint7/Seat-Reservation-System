package com.paytm.reservation.repository;
import com.paytm.reservation.model.Seat;
import com.paytm.reservation.model.SeatId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, SeatId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s
            FROM Seat s
            WHERE s.showId = :showId
              AND s.seatNumber IN :seatNumbers
            ORDER BY s.seatNumber
            """)
    List<Seat> findSeatsForUpdate(
            @Param("showId") Long showId,
            @Param("seatNumbers") List<String> seatNumbers
    );
}