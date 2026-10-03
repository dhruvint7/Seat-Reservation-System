package com.paytm.reservation.service;

import com.paytm.reservation.dto.CreateShowRequest;
import com.paytm.reservation.dto.CreateShowResponse;
import com.paytm.reservation.repository.ShowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ShowService {

    private final ShowRepository showRepository;

    public ShowService(ShowRepository showRepository) {
        this.showRepository = showRepository;
    }

    @Transactional
    public CreateShowResponse createShow(CreateShowRequest request) {

        List<String> seats = request.seats().stream()
                .map(String::trim)
                .toList();

        long uniqueSeats = seats.stream().distinct().count();

        if (uniqueSeats != seats.size()) {
            throw new IllegalArgumentException("Duplicate seats are not allowed");
        }

        if (seats.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Seat number cannot be blank");
        }

        Long showId = showRepository.createShow(
                request.name().trim(),
                request.price_paise()
        );

        showRepository.createSeats(showId, seats);

        List<CreateShowResponse.SeatResponse> seatResponses = seats.stream()
                .map(seat -> new CreateShowResponse.SeatResponse(
                        seat,
                        "AVAILABLE"
                ))
                .toList();

        return new CreateShowResponse(
                showId,
                request.name().trim(),
                seatResponses,
                request.price_paise()
        );
    }
}