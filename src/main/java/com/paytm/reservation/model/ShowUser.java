package com.paytm.reservation.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "show_users")
@IdClass(ShowUserId.class)
public class ShowUser {

    @Id
    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Id
    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "seat_count", nullable = false)
    private Integer seatCount;

    public ShowUser(Long showId, String userId) {
        this.showId = showId;
        this.userId = userId;
        this.seatCount = 0;
    }
}