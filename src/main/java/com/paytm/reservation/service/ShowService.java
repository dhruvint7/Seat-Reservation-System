package com.paytm.reservation.service;
import com.paytm.reservation.dto.CreateShowRequest;
import com.paytm.reservation.dto.CreateShowResponse;
import com.paytm.reservation.dto.ShowDetailsResponse;
import com.paytm.reservation.exception.ResourceNotFoundException;
import com.paytm.reservation.model.Seat;
import com.paytm.reservation.model.Show;
import com.paytm.reservation.repository.SeatRepository;
import com.paytm.reservation.repository.ShowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ShowService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    public ShowService(
            ShowRepository showRepository,
            SeatRepository seatRepository
    ) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional
    public CreateShowResponse createShow(CreateShowRequest request) {

        List<String> seats = request.seats().stream()
                .map(String::trim)
                .toList();

        if (seats.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Seat number cannot be blank");
        }

        if (seats.stream().distinct().count() != seats.size()) {
            throw new IllegalArgumentException("Duplicate seats are not allowed");
        }

        Show show = new Show(
                request.name().trim(),
                request.price_paise()
        );

        show = showRepository.save(show);

        Long showId = show.getId();

        List<Seat> seatEntities = seats.stream()
                .map(seat -> new Seat(showId, seat))
                .toList();

        seatRepository.saveAll(seatEntities);

        List<CreateShowResponse.SeatResponse> seatResponses = seats.stream()
                .map(seat -> new CreateShowResponse.SeatResponse(
                        seat,
                        "AVAILABLE"
                ))
                .toList();

        return new CreateShowResponse(
                showId,
                show.getName(),
                seatResponses,
                show.getPricePaise()
        );
    }
    @Transactional(readOnly = true)
    public ShowDetailsResponse getShow(Long showId) {

        Show show = showRepository.findById(showId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Show not found: " + showId));

        List<Seat> seats =
                seatRepository.findByShowIdOrderBySeatNumber(showId);

        int total = seats.size();

        int available = (int) seats.stream()
                .filter(seat -> "AVAILABLE".equals(seat.getStatus()))
                .count();

        int confirmed = (int) seats.stream()
                .filter(seat -> "CONFIRMED".equals(seat.getStatus()))
                .count();

        // Explicit cancellation model: no temporary HELD state.
        int held = 0;

        List<ShowDetailsResponse.SeatStatusResponse> seatResponses =
                seats.stream()
                        .map(seat ->
                                new ShowDetailsResponse.SeatStatusResponse(
                                        seat.getSeatNumber(),
                                        seat.getStatus()
                                ))
                        .toList();

        return new ShowDetailsResponse(
                show.getId(),
                show.getName(),
                show.getPricePaise(),
                seatResponses,
                total,
                available,
                held,
                confirmed
        );
    }
}