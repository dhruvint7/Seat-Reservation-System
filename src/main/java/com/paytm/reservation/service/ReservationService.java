package com.paytm.reservation.service;

import com.paytm.reservation.dto.ReserveRequest;
import com.paytm.reservation.dto.ReserveResponse;
import com.paytm.reservation.exception.ReservationConflictException;
import com.paytm.reservation.exception.ResourceNotFoundException;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
public class ReservationService {

    private static final Logger log =
            LoggerFactory.getLogger(ReservationService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final ShowUserRepository showUserRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    private final Counter confirmedCounter;
    private final Counter seatTakenCounter;
    private final Counter perUserLimitCounter;
    private final Counter idempotentReplayCounter;

    public ReservationService(
            ShowRepository showRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            ReservationSeatRepository reservationSeatRepository,
            ShowUserRepository showUserRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            MeterRegistry meterRegistry
    ) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
        this.showUserRepository = showUserRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;

        this.confirmedCounter = Counter.builder("reservations.confirmed")
                .description("Number of confirmed reservations")
                .register(meterRegistry);

        this.seatTakenCounter = Counter.builder("reservations.declined")
                .tag("reason", "seat_taken")
                .description("Reservations declined because a seat was unavailable")
                .register(meterRegistry);

        this.perUserLimitCounter = Counter.builder("reservations.declined")
                .tag("reason", "per_user_limit")
                .description("Reservations declined because the user limit was exceeded")
                .register(meterRegistry);

        this.idempotentReplayCounter = Counter.builder("reservations.declined")
                .tag("reason", "idempotent_replay")
                .description("Reservation requests served by idempotent replay")
                .register(meterRegistry);
    }

    @Transactional
    public ReserveResponse reserve(
            Long showId,
            String userId,
            ReserveRequest request
    ) {

        List<String> requestedSeats = request.seats()
                .stream()
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();

        if (requestedSeats.isEmpty()) {
            throw new IllegalArgumentException("At least one seat is required");
        }

        log.info(
                "Reservation request received: showId={}, userId={}, seats={}, idempotencyKey={}",
                showId,
                userId,
                requestedSeats,
                request.idempotency_key()
        );

        /*
         * Validate show.
         */
        Show show = showRepository.findById(showId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Show not found: " + showId
                        )
                );

        /*
         * Hash canonical request.
         */
        String requestHash = hashSeats(requestedSeats);

        /*
         * Check idempotency key.
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
             * Same key + different request body.
             */
            if (!existing.getRequestHash().equals(requestHash)) {

                log.info(
                        "Reservation declined: reason=idempotency_key_reused_with_different_request, " +
                                "showId={}, userId={}, idempotencyKey={}",
                        showId,
                        userId,
                        request.idempotency_key()
                );

                throw new ReservationConflictException(
                        "Idempotency key was already used with a different request"
                );
            }

            /*
             * Same key + same request.
             */
            if (existing.getReservationId() == null) {

                log.info(
                        "Reservation idempotency conflict: reservation still processing, " +
                                "showId={}, userId={}, idempotencyKey={}",
                        showId,
                        userId,
                        request.idempotency_key()
                );

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

            idempotentReplayCounter.increment();

            log.info(
                    "Reservation idempotent replay: reservationId={}, showId={}, userId={}, seats={}",
                    originalReservation.getId(),
                    showId,
                    userId,
                    requestedSeats
            );

            return toResponse(
                    originalReservation,
                    requestedSeats
            );
        }

        /*
         * Find or create per-user counter row.
         */
        ShowUser showUser = showUserRepository
                .findById(
                        new com.paytm.reservation.model.ShowUserId(
                                showId,
                                userId
                        )
                )
                .orElseGet(() -> {
                    ShowUser newShowUser =
                            new ShowUser(showId, userId);

                    return showUserRepository.saveAndFlush(newShowUser);
                });

        /*
         * Lock user quota row.
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

            perUserLimitCounter.increment();

            log.info(
                    "Reservation declined: reason=per_user_limit, " +
                            "showId={}, userId={}, requestedSeats={}, currentCount={}, limit={}",
                    showId,
                    userId,
                    requestedCount,
                    currentCount,
                    show.getPerUserLimit()
            );

            throw new ReservationConflictException(
                    "Per-user reservation limit exceeded"
            );
        }

        /*
         * Lock requested seats.
         *
         * requestedSeats is sorted, so concurrent transactions
         * acquire multiple seat locks in deterministic order.
         */
        List<Seat> seats = seatRepository.findSeatsForUpdate(
                showId,
                requestedSeats
        );

        /*
         * Make sure every requested seat exists.
         */
        if (seats.size() != requestedSeats.size()) {

            log.info(
                    "Reservation declined: reason=seat_not_found, " +
                            "showId={}, userId={}, seats={}",
                    showId,
                    userId,
                    requestedSeats
            );

            throw new ReservationConflictException(
                    "One or more requested seats do not exist"
            );
        }

        /*
         * All-or-nothing semantics.
         */
        boolean unavailableSeat = seats.stream()
                .anyMatch(seat ->
                        !"AVAILABLE".equals(seat.getStatus())
                );

        if (unavailableSeat) {

            seatTakenCounter.increment();

            log.info(
                    "Reservation declined: reason=seat_taken, " +
                            "showId={}, userId={}, seats={}",
                    showId,
                    userId,
                    requestedSeats
            );

            throw new ReservationConflictException(
                    "One or more requested seats are already reserved"
            );
        }

        /*
         * Integer paise only.
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
         * Mark seats confirmed.
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

        confirmedCounter.increment();

        log.info(
                "Reservation confirmed: reservationId={}, showId={}, userId={}, " +
                        "seats={}, amountPaise={}",
                reservationId,
                showId,
                userId,
                requestedSeats,
                amountPaise
        );

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

        String canonicalValue =
                String.join(",", seats);

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

    @Transactional
    public ReserveResponse cancel(
            Long reservationId,
            String userId
    ) {

        log.info(
                "Cancellation request received: reservationId={}, userId={}",
                reservationId,
                userId
        );

        Reservation reservation =
                reservationRepository.findForUpdate(reservationId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Reservation not found: " + reservationId
                                )
                        );

        /*
         * Only owner can cancel.
         */
        if (!reservation.getUserId().equals(userId)) {

            log.info(
                    "Cancellation declined: reason=not_owner, " +
                            "reservationId={}, userId={}",
                    reservationId,
                    userId
            );

            throw new ReservationConflictException(
                    "You are not allowed to cancel this reservation"
            );
        }

        List<ReservationSeat> reservationSeats =
                reservationSeatRepository
                        .findByReservationIdOrderBySeatNumber(
                                reservationId
                        );

        /*
         * Cancellation is idempotent.
         */
        if ("CANCELLED".equals(reservation.getStatus())) {

            log.info(
                    "Cancellation replay: reservationId={}, userId={}",
                    reservationId,
                    userId
            );

            return toResponse(
                    reservation,
                    reservationSeats.stream()
                            .map(ReservationSeat::getSeatNumber)
                            .toList()
            );
        }

        /*
         * Lock seats belonging to reservation.
         */
        List<Seat> seats =
                seatRepository.findSeatsForReservationForUpdate(
                        reservation.getShowId(),
                        reservationId
                );

        if (seats.size() != reservationSeats.size()) {
            throw new IllegalStateException(
                    "Reservation seat data is inconsistent"
            );
        }

        /*
         * Lock user's quota row.
         */
        ShowUser showUser =
                showUserRepository.findForUpdate(
                        reservation.getShowId(),
                        userId
                ).orElseThrow(() ->
                        new IllegalStateException(
                                "User reservation counter not found"
                        )
                );

        /*
         * Release seats.
         */
        for (Seat seat : seats) {
            seat.setStatus("AVAILABLE");
            seat.setReservationId(null);
        }

        seatRepository.saveAll(seats);

        /*
         * Release user's quota.
         */
        showUser.setSeatCount(
                showUser.getSeatCount() - seats.size()
        );

        showUserRepository.save(showUser);

        /*
         * Mark reservation cancelled.
         */
        reservation.setStatus("CANCELLED");
        reservation.setCancelledAt(
                LocalDateTime.now()
        );

        reservationRepository.save(reservation);

        log.info(
                "Reservation cancelled: reservationId={}, showId={}, userId={}, seats={}",
                reservationId,
                reservation.getShowId(),
                userId,
                reservationSeats.stream()
                        .map(ReservationSeat::getSeatNumber)
                        .toList()
        );

        return toResponse(
                reservation,
                reservationSeats.stream()
                        .map(ReservationSeat::getSeatNumber)
                        .toList()
        );
    }
}