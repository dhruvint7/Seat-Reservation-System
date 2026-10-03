package com.paytm.reservation.service;

import com.paytm.reservation.dto.ReserveRequest;
import com.paytm.reservation.dto.ReserveResponse;
import com.paytm.reservation.exception.ReservationConflictException;
import com.paytm.reservation.model.IdempotencyKey;
import com.paytm.reservation.model.Reservation;
import com.paytm.reservation.model.ReservationSeat;
import com.paytm.reservation.model.Seat;
import com.paytm.reservation.model.Show;
import com.paytm.reservation.model.ShowUser;
import com.paytm.reservation.repository.IdempotencyKeyRepository;
import com.paytm.reservation.repository.ReservationRepository;
import com.paytm.reservation.repository.ReservationSeatRepository;
import com.paytm.reservation.repository.SeatRepository;
import com.paytm.reservation.repository.ShowRepository;
import com.paytm.reservation.repository.ShowUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class ReservationService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final ShowUserRepository showUserRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public ReservationService(
            ShowRepository showRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            ReservationSeatRepository reservationSeatRepository,
            ShowUserRepository showUserRepository,
            IdempotencyKeyRepository idempotencyKeyRepository
    ) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
        this.showUserRepository = showUserRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Transactional
    public ReserveResponse reserve(
            Long showId,
            String userId,
            ReserveRequest request
    ) {

        /*
         * Canonicalize requested seats.
         *
         * Example:
         * ["A2", "A1", "A2"]
         * becomes
         * ["A1", "A2"]
         *
         * Sorting is important because all transactions acquire
         * seat locks in the same order, reducing deadlock risk.
         */
        List<String> requestedSeats = request.seats()
                .stream()
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();

        if (requestedSeats.isEmpty()) {
            throw new IllegalArgumentException("At least one seat is required");
        }

        /*
         * Validate show.
         */
        Show show = showRepository.findById(showId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Show not found: " + showId)
                );

        /*
         * Hash the canonical request.
         *
         * Same idempotency key + same seats
         *      -> replay
         *
         * Same idempotency key + different seats
         *      -> conflict
         */
        String requestHash = hashSeats(requestedSeats);

        /*
         * Check whether this idempotency key was already used.
         */
        var existingIdempotencyKey =
                idempotencyKeyRepository.findByShowIdAndUserIdAndIdempotencyKey(
                        showId,
                        userId,
                        request.idempotency_key()
                );

        if (existingIdempotencyKey.isPresent()) {

            IdempotencyKey existing = existingIdempotencyKey.get();

            /*
             * Same key but different request body.
             */
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ReservationConflictException(
                        "Idempotency key was already used with a different request"
                );
            }

            /*
             * Same request -> return original reservation.
             */
            if (existing.getReservationId() == null) {
                throw new ReservationConflictException(
                        "Reservation is still being processed"
                );
            }

            Reservation originalReservation =
                    reservationRepository.findById(existing.getReservationId())
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Original reservation not found"
                                    )
                            );

            return toResponse(
                    originalReservation,
                    requestedSeats
            );
        }

        /*
         * Find or create the per-user counter row.
         *
         * This row is later locked with SELECT ... FOR UPDATE.
         */
        ShowUser showUser = showUserRepository
                .findById(new com.paytm.reservation.model.ShowUserId(showId, userId))
                .orElseGet(() -> {
                    ShowUser newShowUser = new ShowUser(showId, userId);
                    return showUserRepository.saveAndFlush(newShowUser);
                });

        /*
         * Lock the user quota row.
         *
         * This serializes concurrent reservations from the same user.
         */
        showUser = showUserRepository
                .findForUpdate(showId, userId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Unable to lock user reservation counter"
                        )
                );

        /*
         * Enforce per-user limit.
         */
        int requestedCount = requestedSeats.size();
        int currentCount = showUser.getSeatCount();

        if (currentCount + requestedCount > show.getPerUserLimit()) {
            throw new ReservationConflictException(
                    "Per-user reservation limit exceeded"
            );
        }

        /*
         * Lock all requested seats.
         *
         * SeatRepository uses PESSIMISTIC_WRITE.
         *
         * Because requestedSeats is sorted, all concurrent
         * transactions acquire multiple seat locks in the same order.
         */
        List<Seat> seats = seatRepository.findSeatsForUpdate(
                showId,
                requestedSeats
        );

        /*
         * Make sure every requested seat actually exists.
         */
        if (seats.size() != requestedSeats.size()) {
            throw new ReservationConflictException(
                    "One or more requested seats do not exist"
            );
        }

        /*
         * All-or-nothing semantics:
         *
         * If even one requested seat is unavailable,
         * the entire reservation is rejected.
         */
        boolean unavailableSeat = seats.stream()
                .anyMatch(seat -> !"AVAILABLE".equals(seat.getStatus()));

        if (unavailableSeat) {
            throw new ReservationConflictException(
                    "One or more requested seats are already reserved"
            );
        }

        /*
         * Calculate amount using integer paise only.
         */
        long amountPaise =
                show.getPricePaise() * requestedCount;

        /*
         * Create reservation.
         */
        Reservation reservation = new Reservation(
                showId,
                userId,
                amountPaise,
                "CONFIRMED"
        );

        reservation = reservationRepository.save(reservation);

        /*
         * IMPORTANT:
         *
         * reservation is reassigned above, so it is not effectively final.
         * Lambda expressions cannot directly capture it.
         *
         * Keep the ID in a final variable.
         */
        final Long reservationId = reservation.getId();

        /*
         * Create reservation-seat mappings.
         */
        List<ReservationSeat> reservationSeats = requestedSeats
                .stream()
                .map(seatNumber ->
                        new ReservationSeat(
                                reservationId,
                                showId,
                                seatNumber
                        )
                )
                .toList();

        reservationSeatRepository.saveAll(reservationSeats);

        /*
         * Mark seats as confirmed.
         */
        for (Seat seat : seats) {
            seat.setStatus("CONFIRMED");
            seat.setReservationId(reservationId);
        }

        seatRepository.saveAll(seats);

        /*
         * Update user's reservation count.
         */
        showUser.setSeatCount(
                currentCount + requestedCount
        );

        showUserRepository.save(showUser);

        /*
         * Store idempotency mapping.
         */
        IdempotencyKey idempotencyKey = new IdempotencyKey(
                showId,
                userId,
                request.idempotency_key(),
                requestHash
        );

        idempotencyKey.setReservationId(reservationId);

        idempotencyKeyRepository.save(idempotencyKey);

        /*
         * Return confirmed reservation.
         */
        return toResponse(
                reservation,
                requestedSeats
        );
    }

    private ReserveResponse toResponse(
            Reservation reservation,
            List<String> seats
    ) {
        return new ReserveResponse(
                reservation.getId(),
                reservation.getShowId(),
                reservation.getUserId(),
                seats,
                reservation.getAmountPaise(),
                reservation.getStatus()
        );
    }

    private String hashSeats(List<String> seats) {

        String canonicalValue = String.join(",", seats);

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    canonicalValue.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    exception
            );
        }
    }
}