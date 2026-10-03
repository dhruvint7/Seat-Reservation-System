package com.paytm.reservation.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "shows")
@Data
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_paise", nullable = false)
    private Long pricePaise;

    @Column(name = "per_user_limit", nullable = false)
    private Integer perUserLimit;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Show() {
    }

    public Show(String name, Long pricePaise) {
        this.name = name;
        this.pricePaise = pricePaise;
        this.perUserLimit = 4;
    }
}