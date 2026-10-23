package edu.cmu.cs214.campusreserve.core.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** The fields an owner wants to replace on an existing booking. */
public record BookingModificationRequest(
    String bookingId,
    String requesterId,
    Optional<TimeInterval> interval,
    OptionalInt attendees) {

    public BookingModificationRequest {
        bookingId = Identifiers.require(bookingId, "booking id");
        requesterId = Identifiers.require(requesterId, "requester id");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(attendees, "attendees");
        if (attendees.isPresent() && attendees.getAsInt() <= 0) {
            throw new IllegalArgumentException("Attendees must be positive: " + attendees.getAsInt());
        }
    }

    /** Replaces both editable fields. */
    public static BookingModificationRequest of(
        String bookingId, String requesterId, TimeInterval interval, int attendees) {
        return new BookingModificationRequest(
            bookingId, requesterId, Optional.of(interval), OptionalInt.of(attendees));
    }

    /** Replaces only the interval. */
    public static BookingModificationRequest intervalOnly(
        String bookingId, String requesterId, TimeInterval interval) {
        return new BookingModificationRequest(
            bookingId, requesterId, Optional.of(interval), OptionalInt.empty());
    }

    /** Replaces only the attendee count. */
    public static BookingModificationRequest attendeesOnly(
        String bookingId, String requesterId, int attendees) {
        return new BookingModificationRequest(
            bookingId, requesterId, Optional.empty(), OptionalInt.of(attendees));
    }

    /** Requests no field changes. */
    public static BookingModificationRequest unchanged(String bookingId, String requesterId) {
        return new BookingModificationRequest(
            bookingId, requesterId, Optional.empty(), OptionalInt.empty());
    }
}
