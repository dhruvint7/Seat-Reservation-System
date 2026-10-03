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
import io.micrometer.core.instrument.Gauge;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ReservationService {

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

    private final AtomicLong availableSeatsGauge = new AtomicLong();

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

        Gauge.builder(
                        "reservations.seats.available",
                        availableSeatsGauge,
                        AtomicLong::get
                )
                .description("Number of currently available seats")
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
            throw new IllegalArgumentException(
                    "At least one seat is required"
            );
        }

        Show show = showRepository.findById(showId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Show not found: " + showId
                        )
                );

        String requestHash = hashSeats(requestedSeats);

        var existingIdempotencyKey =
                idempotencyKeyRepository
                        .findByShowIdAndUserIdAndIdempotencyKey(
                                showId,
                                userId,
                                request.idempotency_key()
                        );

        if (existingIdempotencyKey.isPresent()) {

            IdempotencyKey existing =
                    existingIdempotencyKey.get();

            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ReservationConflictException(
                        "Idempotency key was already used with a different request"
                );
            }

            if (existing.getReservationId() == null) {
                throw new ReservationConflictException(
                        "Reservation is still being processed"
                );
            }

            Reservation originalReservation =
                    reservationRepository.findById(
                                    existing.getReservationId()
                            )
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Original reservation not found"
                                    )
                            );

            idempotentReplayCounter.increment();

            return toResponse(
                    originalReservation,
                    requestedSeats
            );
        }

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

                    return showUserRepository.saveAndFlush(
                            newShowUser
                    );
                });

        showUser = showUserRepository
                .findForUpdate(showId, userId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Unable to lock user reservation counter"
                        )
                );

        int requestedCount = requestedSeats.size();
        int currentCount = showUser.getSeatCount();

        if (currentCount + requestedCount
                > show.getPerUserLimit()) {

            perUserLimitCounter.increment();

            throw new ReservationConflictException(
                    "Per-user reservation limit exceeded"
            );
        }

        List<Seat> seats =
                seatRepository.findSeatsForUpdate(
                        showId,
                        requestedSeats
                );

        if (seats.size() != requestedSeats.size()) {
            throw new ReservationConflictException(
                    "One or more requested seats do not exist"
            );
        }

        boolean unavailableSeat = seats.stream()
                .anyMatch(seat ->
                        !"AVAILABLE".equals(seat.getStatus())
                );

        if (unavailableSeat) {

            seatTakenCounter.increment();

            throw new ReservationConflictException(
                    "One or more requested seats are already reserved"
            );
        }

        long amountPaise =
                show.getPricePaise() * requestedCount;

        Reservation reservation =
                new Reservation(
                        showId,
                        userId,
                        amountPaise,
                        "CONFIRMED"
                );

        reservation =
                reservationRepository.save(reservation);

        final Long reservationId =
                reservation.getId();

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

        for (Seat seat : seats) {
            seat.setStatus("CONFIRMED");
            seat.setReservationId(reservationId);
        }

        seatRepository.saveAll(seats);

        showUser.setSeatCount(
                currentCount + requestedCount
        );

        showUserRepository.save(showUser);

        IdempotencyKey idempotencyKey =
                new IdempotencyKey(
                        showId,
                        userId,
                        request.idempotency_key(),
                        requestHash
                );

        idempotencyKey.setReservationId(
                reservationId
        );

        idempotencyKeyRepository.save(
                idempotencyKey
        );

        confirmedCounter.increment();

        refreshAvailableSeatsGauge();

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

            byte[] hash =
                    digest.digest(
                            canonicalValue.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException exception) {

            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    exception
            );
        }
    }

    private void refreshAvailableSeatsGauge() {

        availableSeatsGauge.set(
                seatRepository.countAvailableSeats()
        );
    }

    @Transactional
    public ReserveResponse cancel(
            Long reservationId,
            String userId
    ) {

        Reservation reservation =
                reservationRepository.findForUpdate(
                                reservationId
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Reservation not found: "
                                                + reservationId
                                )
                        );

        if (!reservation.getUserId().equals(userId)) {

            throw new ReservationConflictException(
                    "You are not allowed to cancel this reservation"
            );
        }

        List<ReservationSeat> reservationSeats =
                reservationSeatRepository
                        .findByReservationIdOrderBySeatNumber(
                                reservationId
                        );

        if ("CANCELLED".equals(
                reservation.getStatus()
        )) {

            return toResponse(
                    reservation,
                    reservationSeats.stream()
                            .map(
                                    ReservationSeat::getSeatNumber
                            )
                            .toList()
            );
        }

        List<Seat> seats =
                seatRepository
                        .findSeatsForReservationForUpdate(
                                reservation.getShowId(),
                                reservationId
                        );

        if (seats.size()
                != reservationSeats.size()) {

            throw new IllegalStateException(
                    "Reservation seat data is inconsistent"
            );
        }

        ShowUser showUser =
                showUserRepository.findForUpdate(
                                reservation.getShowId(),
                                userId
                        )
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "User reservation counter not found"
                                )
                        );

        for (Seat seat : seats) {

            seat.setStatus("AVAILABLE");
            seat.setReservationId(null);
        }

        seatRepository.saveAll(seats);

        showUser.setSeatCount(
                showUser.getSeatCount()
                        - seats.size()
        );

        showUserRepository.save(showUser);

        reservation.setStatus("CANCELLED");
        reservation.setCancelledAt(
                java.time.LocalDateTime.now()
        );

        reservationRepository.save(
                reservation
        );

        refreshAvailableSeatsGauge();

        return toResponse(
                reservation,
                reservationSeats.stream()
                        .map(
                                ReservationSeat::getSeatNumber
                        )
                        .toList()
        );
    }
}