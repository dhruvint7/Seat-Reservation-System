package com.paytm.reservation.service;

import com.paytm.reservation.dto.ReserveRequest;
import com.paytm.reservation.dto.ReserveResponse;
import com.paytm.reservation.exception.ReservationConflictException;
import com.paytm.reservation.model.Seat;
import com.paytm.reservation.model.Show;
import com.paytm.reservation.repository.SeatRepository;
import com.paytm.reservation.repository.ShowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class ReservationIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("seat_reservation")
                    .withUsername("reservation")
                    .withPassword("reservation");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                mysql::getJdbcUrl
        );
        registry.add(
                "spring.datasource.username",
                mysql::getUsername
        );
        registry.add(
                "spring.datasource.password",
                mysql::getPassword
        );
    }

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private SeatRepository seatRepository;

    private Long createShow(String... seatNumbers) {

        Show show = new Show(
                "Integration Test Show",
                10000L
        );

        show = showRepository.saveAndFlush(show);

        Long showId = show.getId();

        List<Seat> seats = new ArrayList<>();

        for (String seatNumber : seatNumbers) {
            seats.add(new Seat(showId, seatNumber));
        }

        seatRepository.saveAllAndFlush(seats);

        return showId;
    }

    @Test
    void concurrentHotSeat_shouldAllowExactlyOneReservation()
            throws Exception {

        Long showId = createShow("A1");

        int requestCount = 20;

        ExecutorService executor =
                Executors.newFixedThreadPool(requestCount);

        CountDownLatch start =
                new CountDownLatch(1);

        List<Future<Boolean>> futures =
                new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {

            final int userNumber = i;

            futures.add(
                    executor.submit(() -> {

                        start.await();

                        try {

                            ReserveRequest request =
                                    new ReserveRequest(
                                            List.of("A1"),
                                            "hot-seat-" + userNumber
                                    );

                            reservationService.reserve(
                                    showId,
                                    "user-" + userNumber,
                                    request
                            );

                            return true;

                        } catch (ReservationConflictException exception) {
                            return false;
                        }
                    })
            );
        }

        start.countDown();

        int successfulReservations = 0;

        for (Future<Boolean> future : futures) {

            if (future.get()) {
                successfulReservations++;
            }
        }

        executor.shutdown();

        assertEquals(
                1,
                successfulReservations,
                "Exactly one request should reserve the hot seat"
        );

        List<Seat> seats =
                seatRepository.findByShowIdOrderBySeatNumber(showId);

        assertEquals(1, seats.size());
        assertEquals("CONFIRMED", seats.get(0).getStatus());
    }

    @Test
    void concurrentSameUser_shouldRespectPerUserLimit()
            throws Exception {

        Long showId =
                createShow("A1", "A2", "A3", "A4", "A5");

        int requestCount = 5;

        ExecutorService executor =
                Executors.newFixedThreadPool(requestCount);

        CountDownLatch start =
                new CountDownLatch(1);

        List<Future<Boolean>> futures =
                new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {

            final int seatNumber = i + 1;

            futures.add(
                    executor.submit(() -> {

                        start.await();

                        try {

                            ReserveRequest request =
                                    new ReserveRequest(
                                            List.of("A" + seatNumber),
                                            "limit-" + seatNumber
                                    );

                            reservationService.reserve(
                                    showId,
                                    "same-user",
                                    request
                            );

                            return true;

                        } catch (ReservationConflictException exception) {
                            return false;
                        }
                    })
            );
        }

        start.countDown();

        int successfulReservations = 0;

        for (Future<Boolean> future : futures) {

            if (future.get()) {
                successfulReservations++;
            }
        }

        executor.shutdown();

        assertEquals(
                4,
                successfulReservations,
                "User must not reserve more than 4 seats"
        );
    }

    @Test
    void sameIdempotencyKey_shouldReturnOriginalReservation() {

        Long showId =
                createShow("A1", "A2");

        ReserveRequest request =
                new ReserveRequest(
                        List.of("A1"),
                        "idem-123"
                );

        ReserveResponse first =
                reservationService.reserve(
                        showId,
                        "user-1",
                        request
                );

        ReserveResponse second =
                reservationService.reserve(
                        showId,
                        "user-1",
                        request
                );

        assertEquals(
                first.reservationId(),
                second.reservationId()
        );

        assertEquals(
                first.amountPaise(),
                second.amountPaise()
        );

        assertEquals(
                "CONFIRMED",
                second.status()
        );
    }

    @Test
    void sameIdempotencyKeyWithDifferentSeats_shouldConflict() {

        Long showId =
                createShow("A1", "A2");

        reservationService.reserve(
                showId,
                "user-1",
                new ReserveRequest(
                        List.of("A1"),
                        "same-key"
                )
        );

        assertThrows(
                ReservationConflictException.class,
                () -> reservationService.reserve(
                        showId,
                        "user-1",
                        new ReserveRequest(
                                List.of("A2"),
                                "same-key"
                        )
                )
        );
    }

    @Test
    void cancel_shouldReleaseSeats() {

        Long showId =
                createShow("A1");

        ReserveResponse reservation =
                reservationService.reserve(
                        showId,
                        "user-1",
                        new ReserveRequest(
                                List.of("A1"),
                                "cancel-key"
                        )
                );

        assertEquals(
                "CONFIRMED",
                seatRepository
                        .findByShowIdOrderBySeatNumber(showId)
                        .get(0)
                        .getStatus()
        );

        reservationService.cancel(
                reservation.reservationId(),
                "user-1"
        );

        Seat seat =
                seatRepository
                        .findByShowIdOrderBySeatNumber(showId)
                        .get(0);

        assertEquals(
                "AVAILABLE",
                seat.getStatus()
        );

        assertNull(
                seat.getReservationId()
        );
    }
}