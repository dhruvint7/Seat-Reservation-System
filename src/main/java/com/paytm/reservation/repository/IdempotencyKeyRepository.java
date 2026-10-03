package com.paytm.reservation.repository;

import com.paytm.reservation.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyKeyRepository
        extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByShowIdAndUserIdAndIdempotencyKey(
            Long showId,
            String userId,
            String idempotencyKey
    );
}