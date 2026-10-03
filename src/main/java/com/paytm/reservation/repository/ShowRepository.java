package com.paytm.reservation.repository;

import com.paytm.reservation.model.Show;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowRepository extends JpaRepository<Show, Long> {
}