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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ShowRepository showRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationSeatRepository reservationSeatRepository;

    @Mock
    private ShowUserRepository showUserRepository;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                showRepository,
                seatRepository,
                reservationRepository,
                reservationSeatRepository,
                showUserRepository,
                idempotencyKeyRepository,
                new SimpleMeterRegistry()
        );
    }

    @Test
    void reserve_shouldConfirmAvailableSeat() {
        Long showId = 1L;
        String userId = "user1";

        Show show = mockShow();

        when(show.getPricePaise()).thenReturn(1000L);
        when(show.getPerUserLimit()).thenReturn(4);

        Seat seat = mock(Seat.class);
        when(seat.getStatus()).thenReturn("AVAILABLE");

        ShowUser showUser = mock(ShowUser.class);
        when(showUser.getSeatCount()).thenReturn(0);

        Reservation reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn(100L);
        when(reservation.getShowId()).thenReturn(showId);
        when(reservation.getUserId()).thenReturn(userId);
        when(reservation.getAmountPaise()).thenReturn(1000L);
        when(reservation.getStatus()).thenReturn("CONFIRMED");

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository
                .findByShowIdAndUserIdAndIdempotencyKey(
                        showId,
                        userId,
                        "key-1"))
                .thenReturn(Optional.empty());

        when(showUserRepository.findForUpdate(showId, userId))
                .thenReturn(Optional.of(showUser));

        when(seatRepository.findSeatsForUpdate(
                eq(showId),
                anyList()))
                .thenReturn(List.of(seat));

        when(reservationRepository.save(any(Reservation.class)))
                .thenReturn(reservation);

        when(seatRepository.countAvailableSeats())
                .thenReturn(9L);

        ReserveRequest request =
                new ReserveRequest(
                        List.of("A1"),
                        "key-1"
                );

        ReserveResponse response =
                reservationService.reserve(
                        showId,
                        userId,
                        request
                );

        assertEquals(100L, response.reservationId());
        assertEquals(showId, response.showId());
        assertEquals(userId, response.userId());
        assertEquals(List.of("A1"), response.seats());
        assertEquals(1000L, response.amountPaise());
        assertEquals("CONFIRMED", response.status());

        verify(reservationRepository)
                .save(any(Reservation.class));

        verify(reservationSeatRepository)
                .saveAll(anyList());

        verify(seatRepository)
                .saveAll(anyList());

        verify(showUserRepository)
                .save(showUser);

        verify(idempotencyKeyRepository)
                .save(any(IdempotencyKey.class));
    }

    @Test
    void reserve_shouldRejectAlreadyBookedSeat() {
        Long showId = 1L;
        String userId = "user1";

        Show show = mockShow();

        when(show.getPerUserLimit()).thenReturn(4);

        Seat seat = mock(Seat.class);
        when(seat.getStatus()).thenReturn("CONFIRMED");

        ShowUser showUser = mock(ShowUser.class);
        when(showUser.getSeatCount()).thenReturn(0);

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository
                .findByShowIdAndUserIdAndIdempotencyKey(
                        showId,
                        userId,
                        "key-1"))
                .thenReturn(Optional.empty());

        when(showUserRepository.findForUpdate(showId, userId))
                .thenReturn(Optional.of(showUser));

        when(seatRepository.findSeatsForUpdate(
                eq(showId),
                anyList()))
                .thenReturn(List.of(seat));

        ReserveRequest request =
                new ReserveRequest(
                        List.of("A1"),
                        "key-1"
                );

        assertThrows(
                ReservationConflictException.class,
                () -> reservationService.reserve(
                        showId,
                        userId,
                        request
                )
        );

        verify(reservationRepository, never())
                .save(any());

        verify(reservationSeatRepository, never())
                .saveAll(anyList());
    }

    @Test
    void reserve_shouldEnforcePerUserLimit() {
        Long showId = 1L;
        String userId = "user1";

        Show show = mockShow();

        when(show.getPerUserLimit()).thenReturn(4);

        ShowUser showUser = mock(ShowUser.class);
        when(showUser.getSeatCount()).thenReturn(4);

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository
                .findByShowIdAndUserIdAndIdempotencyKey(
                        showId,
                        userId,
                        "key-1"))
                .thenReturn(Optional.empty());

        when(showUserRepository.findForUpdate(showId, userId))
                .thenReturn(Optional.of(showUser));

        ReserveRequest request =
                new ReserveRequest(
                        List.of("A1"),
                        "key-1"
                );

        assertThrows(
                ReservationConflictException.class,
                () -> reservationService.reserve(
                        showId,
                        userId,
                        request
                )
        );

        verify(seatRepository, never())
                .findSeatsForUpdate(anyLong(), anyList());

        verify(reservationRepository, never())
                .save(any());
    }

    @Test
    void reserve_shouldReturnOriginalReservationForIdempotentReplay() {
        Long showId = 1L;
        String userId = "user1";

        Show show = mockShow();

        IdempotencyKey existingKey = mock(IdempotencyKey.class);

        when(existingKey.getRequestHash())
                .thenReturn(sha256("A1"));

        when(existingKey.getReservationId())
                .thenReturn(100L);

        Reservation originalReservation =
                mock(Reservation.class);

        when(originalReservation.getId())
                .thenReturn(100L);

        when(originalReservation.getShowId())
                .thenReturn(showId);

        when(originalReservation.getUserId())
                .thenReturn(userId);

        when(originalReservation.getAmountPaise())
                .thenReturn(1000L);

        when(originalReservation.getStatus())
                .thenReturn("CONFIRMED");

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository
                .findByShowIdAndUserIdAndIdempotencyKey(
                        showId,
                        userId,
                        "key-1"))
                .thenReturn(Optional.of(existingKey));

        when(reservationRepository.findById(100L))
                .thenReturn(Optional.of(originalReservation));

        ReserveRequest request =
                new ReserveRequest(
                        List.of("A1"),
                        "key-1"
                );

        ReserveResponse response =
                reservationService.reserve(
                        showId,
                        userId,
                        request
                );

        assertEquals(100L, response.reservationId());
        assertEquals(showId, response.showId());
        assertEquals(userId, response.userId());
        assertEquals(List.of("A1"), response.seats());
        assertEquals(1000L, response.amountPaise());
        assertEquals("CONFIRMED", response.status());

        verify(reservationRepository)
                .findById(100L);

        verify(reservationRepository, never())
                .save(any());

        verify(seatRepository, never())
                .findSeatsForUpdate(anyLong(), anyList());
    }

    @Test
    void reserve_shouldRejectSameIdempotencyKeyWithDifferentSeats() {
        Long showId = 1L;
        String userId = "user1";

        Show show = mockShow();

        IdempotencyKey existingKey = mock(IdempotencyKey.class);

        when(existingKey.getRequestHash())
                .thenReturn(sha256("A1"));

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository
                .findByShowIdAndUserIdAndIdempotencyKey(
                        showId,
                        userId,
                        "key-1"))
                .thenReturn(Optional.of(existingKey));

        ReserveRequest request =
                new ReserveRequest(
                        List.of("A2"),
                        "key-1"
                );

        assertThrows(
                ReservationConflictException.class,
                () -> reservationService.reserve(
                        showId,
                        userId,
                        request
                )
        );

        verify(reservationRepository, never())
                .save(any());

        verify(seatRepository, never())
                .findSeatsForUpdate(anyLong(), anyList());
    }

    @Test
    void cancel_shouldReleaseReservationSeats() {
        Long reservationId = 100L;
        Long showId = 1L;
        String userId = "user1";

        Reservation reservation =
                mock(Reservation.class);

        when(reservation.getId())
                .thenReturn(reservationId);

        when(reservation.getShowId())
                .thenReturn(showId);

        when(reservation.getUserId())
                .thenReturn(userId);

        when(reservation.getStatus())
                .thenReturn("CONFIRMED");

        ReservationSeat reservationSeat =
                mock(ReservationSeat.class);

        when(reservationSeat.getSeatNumber())
                .thenReturn("A1");

        ShowUser showUser =
                mock(ShowUser.class);

        when(showUser.getSeatCount())
                .thenReturn(1);

        Seat seat =
                mock(Seat.class);

        when(reservationRepository.findForUpdate(
                reservationId))
                .thenReturn(Optional.of(reservation));

        when(reservationSeatRepository
                .findByReservationIdOrderBySeatNumber(
                        reservationId))
                .thenReturn(List.of(reservationSeat));

        when(showUserRepository.findForUpdate(
                showId,
                userId))
                .thenReturn(Optional.of(showUser));

        when(seatRepository.findSeatsForReservationForUpdate(
                showId,
                reservationId))
                .thenReturn(List.of(seat));

        when(seatRepository.countAvailableSeats())
                .thenReturn(10L);

        ReserveResponse response =
                reservationService.cancel(
                        reservationId,
                        userId
                );

        verify(seat).setStatus("AVAILABLE");
        verify(seat).setReservationId(null);

        verify(seatRepository)
                .saveAll(List.of(seat));

        verify(showUser)
                .setSeatCount(0);

        verify(reservation)
                .setStatus("CANCELLED");

        verify(reservationRepository)
                .save(reservation);

        assertEquals(
                reservationId,
                response.reservationId()
        );
    }

    @Test
    void cancel_shouldRejectNonOwner() {
        Long reservationId = 100L;

        Reservation reservation =
                mock(Reservation.class);

        when(reservation.getUserId())
                .thenReturn("owner");

        when(reservationRepository.findForUpdate(
                reservationId))
                .thenReturn(Optional.of(reservation));

        assertThrows(
                ReservationConflictException.class,
                () -> reservationService.cancel(
                        reservationId,
                        "different-user"
                )
        );

        verify(seatRepository, never())
                .findSeatsForReservationForUpdate(
                        anyLong(),
                        anyLong()
                );

        verify(reservationRepository, never())
                .save(any());
    }

    private Show mockShow() {
        return mock(Show.class);
    }

    private String sha256(String value) {
        try {
            var digest =
                    java.security.MessageDigest
                            .getInstance("SHA-256");

            return java.util.HexFormat
                    .of()
                    .formatHex(
                            digest.digest(
                                    value.getBytes(
                                            java.nio.charset.StandardCharsets.UTF_8
                                    )
                            )
                    );

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}