package com.paytm.reservation.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class ShowRepository {

    private final JdbcTemplate jdbcTemplate;

    public ShowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long createShow(String name, Long pricePaise) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO shows (name, price_paise)
                    VALUES (?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );

            ps.setString(1, name);
            ps.setLong(2, pricePaise);
            return ps;
        }, keyHolder);

        return keyHolder.getKey().longValue();
    }

    public void createSeats(Long showId, List<String> seats) {
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO seats (show_id, seat_number, status)
                VALUES (?, ?, 'AVAILABLE')
                """,
                seats,
                seats.size(),
                (ps, seatNumber) -> {
                    ps.setLong(1, showId);
                    ps.setString(2, seatNumber);
                }
        );
    }
}