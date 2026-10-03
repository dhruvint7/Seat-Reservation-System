package com.paytm.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record CreateShowRequest(
        @NotBlank String name,
        @NotEmpty List<String> seats,
        @Positive Long price_paise
) {
}