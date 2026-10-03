package com.paytm.reservation.repository;

import com.paytm.reservation.model.ShowUser;
import com.paytm.reservation.model.ShowUserId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ShowUserRepository extends JpaRepository<ShowUser, ShowUserId> {

    /*
     * Atomically create the quota row if it does not exist.
     *
     * If another concurrent request already created it,
     * this becomes a no-op instead of throwing duplicate-key error.
     */
    @Modifying
    @Query(value = """
            INSERT INTO show_users (show_id, user_id, seat_count)
            VALUES (:showId, :userId, 0)
            ON DUPLICATE KEY UPDATE seat_count = show_users.seat_count
            """, nativeQuery = true)
    int ensureRow(
            @Param("showId") Long showId,
            @Param("userId") String userId
    );

    /*
     * Lock the user's quota row.
     *
     * This serializes concurrent reservations from the same user.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT su
            FROM ShowUser su
            WHERE su.showId = :showId
              AND su.userId = :userId
            """)
    Optional<ShowUser> findForUpdate(
            @Param("showId") Long showId,
            @Param("userId") String userId
    );
}