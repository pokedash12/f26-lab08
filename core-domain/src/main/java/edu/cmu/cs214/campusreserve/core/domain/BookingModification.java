package edu.cmu.cs214.campusreserve.core.domain;

import java.time.Instant;
import java.util.Objects;

/** One recorded change to a booking, preserving both versions of its details. */
public record BookingModification(
    TimeInterval oldInterval,
    int oldAttendees,
    TimeInterval newInterval,
    int newAttendees,
    Instant modifiedAt) {

    public BookingModification {
        Objects.requireNonNull(oldInterval, "oldInterval");
        Objects.requireNonNull(newInterval, "newInterval");
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        requirePositive(oldAttendees, "oldAttendees");
        requirePositive(newAttendees, "newAttendees");
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
