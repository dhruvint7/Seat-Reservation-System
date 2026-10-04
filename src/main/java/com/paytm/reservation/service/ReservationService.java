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
import io.micrometer.core.instrument.Gauge;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

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

    private final AtomicLong availableSeatsGauge =
            new AtomicLong(0);

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

        this.confirmedCounter =
                Counter.builder("reservations.confirmed")
                        .description("Number of confirmed reservations")
                        .register(meterRegistry);

        this.seatTakenCounter =
                Counter.builder("reservations.declined")
                        .tag("reason", "seat_taken")
                        .description(
                                "Reservations declined because a seat was unavailable"
                        )
                        .register(meterRegistry);

        this.perUserLimitCounter =
                Counter.builder("reservations.declined")
                        .tag("reason", "per_user_limit")
                        .description(
                                "Reservations declined because the user limit was exceeded"
                        )
                        .register(meterRegistry);

        this.idempotentReplayCounter =
                Counter.builder("reservations.declined")
                        .tag("reason", "idempotent_replay")
                        .description(
                                "Reservation requests served by idempotent replay"
                        )
                        .register(meterRegistry);

        Gauge.builder(
                        "reservations.seats.available",
                        availableSeatsGauge,
                        AtomicLong::get
                )
                .description("Number of currently available seats")
                .register(meterRegistry);
    }

    // =========================================================
    // RESERVATION
    // =========================================================

    @Transactional
    public ReserveResponse reserve(
            Long showId,
            String userId,
            ReserveRequest request
    ) {

        List<String> requestedSeats =
                normalizeSeats(request);

        validateRequestedSeats(requestedSeats);

        log.info(
                "Reservation request received: showId={}, userId={}, seats={}, idempotencyKey={}",
                showId,
                userId,
                requestedSeats,
                request.idempotency_key()
        );

        Show show = getShow(showId);

        String requestHash =
                hashSeats(requestedSeats);

        Optional<ReserveResponse> replay =
                checkIdempotency(
                        showId,
                        userId,
                        request.idempotency_key(),
                        requestHash,
                        requestedSeats
                );

        if (replay.isPresent()) {
            return replay.get();
        }

        /*
         * IMPORTANT LOCK ORDER:
         *
         *      showUser -> seats
         *
         * Reservation and cancellation both follow this
         * deterministic order to avoid multi-resource deadlocks.
         */
        ShowUser showUser =
                lockUserQuota(showId, userId);

        validateUserLimit(
                show,
                showUser,
                requestedSeats.size(),
                showId,
                userId
        );

        List<Seat> seats =
                lockRequestedSeats(
                        showId,
                        requestedSeats
                );

        validateSeatsExist(
                seats,
                requestedSeats,
                showId,
                userId
        );

        validateSeatsAvailable(
                seats,
                showId,
                userId,
                requestedSeats
        );

        Reservation reservation =
                createReservation(
                        showId,
                        userId,
                        show.getPricePaise(),
                        requestedSeats.size()
                );

        saveReservationSeats(
                reservation.getId(),
                showId,
                requestedSeats
        );

        confirmSeats(
                seats,
                reservation.getId()
        );

        updateUserQuota(
                showUser,
                requestedSeats.size()
        );

        saveIdempotencyRecord(
                showId,
                userId,
                request.idempotency_key(),
                requestHash,
                reservation.getId()
        );

        confirmedCounter.increment();

        refreshAvailableSeatsGauge();

        log.info(
                "Reservation confirmed: reservationId={}, showId={}, userId={}, seats={}, amountPaise={}",
                reservation.getId(),
                showId,
                userId,
                requestedSeats,
                reservation.getAmountPaise()
        );

        return toResponse(
                reservation,
                requestedSeats
        );
    }

    // =========================================================
    // REQUEST VALIDATION
    // =========================================================

    private List<String> normalizeSeats(
            ReserveRequest request
    ) {

        return request.seats()
                .stream()
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    private void validateRequestedSeats(
            List<String> requestedSeats
    ) {

        if (requestedSeats.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one seat is required"
            );
        }
    }

    private Show getShow(
            Long showId
    ) {

        return showRepository.findById(showId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Show not found: " + showId
                        )
                );
    }

    // =========================================================
    // IDEMPOTENCY
    // =========================================================

    private Optional<ReserveResponse> checkIdempotency(
            Long showId,
            String userId,
            String idempotencyKey,
            String requestHash,
            List<String> requestedSeats
    ) {

        var existingIdempotencyKey =
                idempotencyKeyRepository
                        .findByShowIdAndUserIdAndIdempotencyKey(
                                showId,
                                userId,
                                idempotencyKey
                        );

        if (existingIdempotencyKey.isEmpty()) {
            return Optional.empty();
        }

        IdempotencyKey existing =
                existingIdempotencyKey.get();

        validateIdempotencyRequest(
                existing,
                requestHash,
                showId,
                userId,
                idempotencyKey
        );

        if (existing.getReservationId() == null) {

            log.info(
                    "Reservation idempotency conflict: reservation still processing, " +
                            "showId={}, userId={}, idempotencyKey={}",
                    showId,
                    userId,
                    idempotencyKey
            );

            throw new ReservationConflictException(
                    "Reservation is still being processed"
            );
        }

        Reservation originalReservation =
                reservationRepository.findById(
                        existing.getReservationId()
                ).orElseThrow(() ->
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

        return Optional.of(
                toResponse(
                        originalReservation,
                        requestedSeats
                )
        );
    }

    private void validateIdempotencyRequest(
            IdempotencyKey existing,
            String requestHash,
            Long showId,
            String userId,
            String idempotencyKey
    ) {

        if (existing.getRequestHash().equals(requestHash)) {
            return;
        }

        log.info(
                "Reservation declined: reason=idempotency_key_reused_with_different_request, " +
                        "showId={}, userId={}, idempotencyKey={}",
                showId,
                userId,
                idempotencyKey
        );

        throw new ReservationConflictException(
                "Idempotency key was already used with a different request"
        );
    }

    private void saveIdempotencyRecord(
            Long showId,
            String userId,
            String idempotencyKey,
            String requestHash,
            Long reservationId
    ) {

        IdempotencyKey idempotencyRecord =
                new IdempotencyKey(
                        showId,
                        userId,
                        idempotencyKey,
                        requestHash
                );

        idempotencyRecord.setReservationId(
                reservationId
        );

        idempotencyKeyRepository.save(
                idempotencyRecord
        );
    }

    // =========================================================
    // USER QUOTA
    // =========================================================

    private ShowUser lockUserQuota(
            Long showId,
            String userId
    ) {

        /*
         * Atomically create the counter row if it does not exist.
         *
         * This prevents concurrent requests from both attempting
         * to insert the same show/user row.
         */
        showUserRepository.ensureRow(
                showId,
                userId
        );

        /*
         * Pessimistic lock on the user's quota row.
         *
         * Concurrent reservations for the same user/show
         * therefore serialize here.
         */
        return showUserRepository
                .findForUpdate(
                        showId,
                        userId
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Unable to lock user reservation counter"
                        )
                );
    }

    private void validateUserLimit(
            Show show,
            ShowUser showUser,
            int requestedCount,
            Long showId,
            String userId
    ) {

        int currentCount =
                showUser.getSeatCount();

        int limit =
                show.getPerUserLimit();

        if (currentCount + requestedCount <= limit) {
            return;
        }

        perUserLimitCounter.increment();

        log.info(
                "Reservation declined: reason=per_user_limit, " +
                        "showId={}, userId={}, requestedSeats={}, currentCount={}, limit={}",
                showId,
                userId,
                requestedCount,
                currentCount,
                limit
        );

        throw new ReservationConflictException(
                "Per-user reservation limit exceeded"
        );
    }

    private void updateUserQuota(
            ShowUser showUser,
            int requestedCount
    ) {

        showUser.setSeatCount(
                showUser.getSeatCount() +
                        requestedCount
        );

        showUserRepository.save(
                showUser
        );
    }

    // =========================================================
    // SEAT LOCKING
    // =========================================================

    private List<Seat> lockRequestedSeats(
            Long showId,
            List<String> requestedSeats
    ) {

        /*
         * requestedSeats is already sorted.
         *
         * Repository query also orders by seat number.
         * This gives deterministic multi-seat lock acquisition.
         */
        return seatRepository.findSeatsForUpdate(
                showId,
                requestedSeats
        );
    }

    private void validateSeatsExist(
            List<Seat> seats,
            List<String> requestedSeats,
            Long showId,
            String userId
    ) {

        if (seats.size() == requestedSeats.size()) {
            return;
        }

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

    private void validateSeatsAvailable(
            List<Seat> seats,
            Long showId,
            String userId,
            List<String> requestedSeats
    ) {

        boolean unavailableSeat =
                seats.stream()
                        .anyMatch(seat ->
                                !"AVAILABLE".equals(
                                        seat.getStatus()
                                )
                        );

        if (!unavailableSeat) {
            return;
        }

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

    private void confirmSeats(
            List<Seat> seats,
            Long reservationId
    ) {

        for (Seat seat : seats) {

            seat.setStatus("CONFIRMED");

            seat.setReservationId(
                    reservationId
            );
        }

        seatRepository.saveAll(
                seats
        );
    }

    // =========================================================
    // RESERVATION CREATION
    // =========================================================

    private Reservation createReservation(
            Long showId,
            String userId,
            long pricePaise,
            int requestedCount
    ) {

        long amountPaise =
                pricePaise * requestedCount;

        Reservation reservation =
                new Reservation(
                        showId,
                        userId,
                        amountPaise,
                        "CONFIRMED"
                );

        return reservationRepository.save(
                reservation
        );
    }

    private void saveReservationSeats(
            Long reservationId,
            Long showId,
            List<String> requestedSeats
    ) {

        List<ReservationSeat> reservationSeats =
                requestedSeats
                        .stream()
                        .map(seatNumber ->
                                new ReservationSeat(
                                        reservationId,
                                        showId,
                                        seatNumber
                                )
                        )
                        .toList();

        reservationSeatRepository.saveAll(
                reservationSeats
        );
    }

    // =========================================================
    // RESPONSE
    // =========================================================

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

    // =========================================================
    // HASHING
    // =========================================================

    private String hashSeats(
            List<String> seats
    ) {

        /*
         * Canonical representation is deterministic because
         * seats were already trimmed, deduplicated and sorted.
         */
        String canonicalValue =
                String.join(",", seats);

        try {

            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash =
                    digest.digest(
                            canonicalValue.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return HexFormat.of()
                    .formatHex(hash);

        } catch (NoSuchAlgorithmException exception) {

            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    exception
            );
        }
    }

    // =========================================================
    // METRICS
    // =========================================================

    private void refreshAvailableSeatsGauge() {

        availableSeatsGauge.set(
                seatRepository.countAvailableSeats()
        );
    }

    // =========================================================
    // CANCELLATION
    // =========================================================

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

        /*
         * Lock reservation first.
         *
         * This serializes concurrent cancellation attempts
         * for the same reservation.
         */
        Reservation reservation =
                reservationRepository.findForUpdate(
                        reservationId
                ).orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Reservation not found: " +
                                        reservationId
                        )
                );

        validateCancellationOwner(
                reservation,
                userId
        );

        List<ReservationSeat> reservationSeats =
                reservationSeatRepository
                        .findByReservationIdOrderBySeatNumber(
                                reservationId
                        );

        /*
         * Cancellation is idempotent.
         */
        if ("CANCELLED".equals(
                reservation.getStatus()
        )) {

            log.info(
                    "Cancellation replay: reservationId={}, userId={}",
                    reservationId,
                    userId
            );

            return toResponse(
                    reservation,
                    reservationSeats.stream()
                            .map(
                                    ReservationSeat::getSeatNumber
                            )
                            .toList()
            );
        }

        /*
         * IMPORTANT LOCK ORDER:
         *
         *      showUser -> seats
         *
         * Same ordering as reserve().
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

        List<Seat> seats =
                seatRepository
                        .findSeatsForReservationForUpdate(
                                reservation.getShowId(),
                                reservationId
                        );

        validateCancellationConsistency(
                seats,
                reservationSeats
        );

        releaseSeats(
                seats
        );

        updateUserQuotaAfterCancellation(
                showUser,
                seats.size()
        );

        markReservationCancelled(
                reservation
        );

        refreshAvailableSeatsGauge();

        List<String> seatNumbers =
                reservationSeats.stream()
                        .map(
                                ReservationSeat::getSeatNumber
                        )
                        .toList();

        log.info(
                "Reservation cancelled: reservationId={}, showId={}, userId={}, seats={}",
                reservationId,
                reservation.getShowId(),
                userId,
                seatNumbers
        );

        return toResponse(
                reservation,
                seatNumbers
        );
    }

    private void validateCancellationOwner(
            Reservation reservation,
            String userId
    ) {

        if (reservation.getUserId().equals(userId)) {
            return;
        }

        log.info(
                "Cancellation declined: reason=not_owner, " +
                        "reservationId={}, userId={}",
                reservation.getId(),
                userId
        );

        throw new ReservationConflictException(
                "You are not allowed to cancel this reservation"
        );
    }

    private void validateCancellationConsistency(
            List<Seat> seats,
            List<ReservationSeat> reservationSeats
    ) {

        if (seats.size() == reservationSeats.size()) {
            return;
        }

        throw new IllegalStateException(
                "Reservation seat data is inconsistent"
        );
    }

    private void releaseSeats(
            List<Seat> seats
    ) {

        for (Seat seat : seats) {

            seat.setStatus("AVAILABLE");

            seat.setReservationId(null);
        }

        seatRepository.saveAll(
                seats
        );
    }

    private void updateUserQuotaAfterCancellation(
            ShowUser showUser,
            int releasedSeatCount
    ) {

        showUser.setSeatCount(
                showUser.getSeatCount() -
                        releasedSeatCount
        );

        showUserRepository.save(
                showUser
        );
    }

    private void markReservationCancelled(
            Reservation reservation
    ) {

        reservation.setStatus(
                "CANCELLED"
        );

        reservation.setCancelledAt(
                LocalDateTime.now()
        );

        reservationRepository.save(
                reservation
        );
    }
}