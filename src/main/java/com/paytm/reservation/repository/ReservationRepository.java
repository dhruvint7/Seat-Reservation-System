package com.paytm.reservation.repository;

import com.paytm.reservation.model.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT r
        FROM Reservation r
        WHERE r.id = :reservationId
        """)
    Optional<Reservation> findForUpdate(
            @Param("reservationId") Long reservationId
    );
}