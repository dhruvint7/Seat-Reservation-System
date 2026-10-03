package com.paytm.reservation.model;

import java.io.Serializable;
import java.util.Objects;

public class ShowUserId implements Serializable {

    private Long showId;
    private String userId;

    public ShowUserId() {
    }

    public ShowUserId(Long showId, String userId) {
        this.showId = showId;
        this.userId = userId;
    }

    public Long getShowId() {
        return showId;
    }

    public void setShowId(Long showId) {
        this.showId = showId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShowUserId that)) return false;

        return Objects.equals(showId, that.showId)
                && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(showId, userId);
    }
}