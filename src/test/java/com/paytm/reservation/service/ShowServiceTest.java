package com.paytm.reservation.service;

import com.paytm.reservation.dto.CreateShowRequest;
import com.paytm.reservation.dto.CreateShowResponse;
import com.paytm.reservation.dto.ShowDetailsResponse;
import com.paytm.reservation.exception.ResourceNotFoundException;
import com.paytm.reservation.model.Seat;
import com.paytm.reservation.model.Show;
import com.paytm.reservation.repository.SeatRepository;
import com.paytm.reservation.repository.ShowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShowServiceTest {

    @Mock
    private ShowRepository showRepository;

    @Mock
    private SeatRepository seatRepository;

    private ShowService showService;

    @BeforeEach
    void setUp() {
        showService = new ShowService(
                showRepository,
                seatRepository
        );
    }

    @Test
    void createShow_shouldCreateShowAndSeats() {
        CreateShowRequest request =
                new CreateShowRequest(
                        "Movie Show",
                        List.of("A1", "A2", "A3"),
                        5000L
                );

        Show show = mock(Show.class);

        when(show.getId()).thenReturn(1L);
        when(show.getName()).thenReturn("Movie Show");
        when(show.getPricePaise()).thenReturn(5000L);

        when(showRepository.save(any(Show.class)))
                .thenReturn(show);

        CreateShowResponse response =
                showService.createShow(request);

        assertEquals(1L, response.showId());
        assertEquals("Movie Show", response.name());
        assertEquals(5000L, response.pricePaise());

        assertEquals(
                3,
                response.seats().size()
        );

        assertEquals(
                "A1",
                response.seats().get(0).seatNumber()
        );

        assertEquals(
                "AVAILABLE",
                response.seats().get(0).status()
        );

        verify(showRepository)
                .save(any(Show.class));

        verify(seatRepository)
                .saveAll(anyList());
    }

    @Test
    void createShow_shouldRejectBlankSeat() {
        CreateShowRequest request =
                new CreateShowRequest(
                        "Movie Show",
                        List.of("A1", " ", "A3"),
                        5000L
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> showService.createShow(request)
        );

        verify(showRepository, never())
                .save(any());

        verify(seatRepository, never())
                .saveAll(anyList());
    }

    @Test
    void createShow_shouldRejectDuplicateSeats() {
        CreateShowRequest request =
                new CreateShowRequest(
                        "Movie Show",
                        List.of("A1", "A2", "A1"),
                        5000L
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> showService.createShow(request)
        );

        verify(showRepository, never())
                .save(any());

        verify(seatRepository, never())
                .saveAll(anyList());
    }

    @Test
    void getShow_shouldReturnCorrectInventoryCounts() {
        Long showId = 1L;

        Show show = mock(Show.class);

        when(show.getId()).thenReturn(showId);
        when(show.getName()).thenReturn("Movie Show");
        when(show.getPricePaise()).thenReturn(5000L);

        Seat availableSeat = mock(Seat.class);
        when(availableSeat.getSeatNumber()).thenReturn("A1");
        when(availableSeat.getStatus()).thenReturn("AVAILABLE");

        Seat confirmedSeat = mock(Seat.class);
        when(confirmedSeat.getSeatNumber()).thenReturn("A2");
        when(confirmedSeat.getStatus()).thenReturn("CONFIRMED");

        Seat anotherAvailableSeat = mock(Seat.class);
        when(anotherAvailableSeat.getSeatNumber()).thenReturn("A3");
        when(anotherAvailableSeat.getStatus()).thenReturn("AVAILABLE");

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(seatRepository.findByShowIdOrderBySeatNumber(showId))
                .thenReturn(List.of(
                        availableSeat,
                        confirmedSeat,
                        anotherAvailableSeat
                ));

        ShowDetailsResponse response =
                showService.getShow(showId);

        assertEquals(showId, response.showId());
        assertEquals("Movie Show", response.name());
        assertEquals(5000L, response.pricePaise());

        assertEquals(3, response.total());
        assertEquals(2, response.available());
        assertEquals(0, response.held());
        assertEquals(1, response.confirmed());

        assertEquals(
                3,
                response.seats().size()
        );

        assertEquals(
                "A1",
                response.seats().get(0).seatNumber()
        );

        assertEquals(
                "AVAILABLE",
                response.seats().get(0).status()
        );

        assertEquals(
                "A2",
                response.seats().get(1).seatNumber()
        );

        assertEquals(
                "CONFIRMED",
                response.seats().get(1).status()
        );
    }

    @Test
    void getShow_shouldThrowWhenShowDoesNotExist() {
        Long showId = 999L;

        when(showRepository.findById(showId))
                .thenReturn(Optional.empty());

        assertThrows(
                ResourceNotFoundException.class,
                () -> showService.getShow(showId)
        );

        verify(seatRepository, never())
                .findByShowIdOrderBySeatNumber(anyLong());
    }
}