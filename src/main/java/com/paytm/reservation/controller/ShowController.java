package com.paytm.reservation.controller;

import com.paytm.reservation.dto.CreateShowRequest;
import com.paytm.reservation.dto.CreateShowResponse;
import com.paytm.reservation.service.ShowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateShowResponse createShow(
            @Valid @RequestBody CreateShowRequest request
    ) {
        return showService.createShow(request);
    }
}