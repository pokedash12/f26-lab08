package edu.cmu.cs214.campusreserve.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** What came of an attempt to modify a booking in place. */
public sealed interface ModificationResult {

    /** The modification succeeded. {@code promotions} may be empty. */
    record Modified(
        Booking booking,
        List<CancellationResult.Promotion> promotions) implements ModificationResult {

        public Modified {
            Objects.requireNonNull(booking, "booking");
            promotions = List.copyOf(promotions);
        }
    }

    /** The modification was refused and the original booking remains unchanged. */
    record Rejected(RejectionReason reason, String detail) implements ModificationResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? reason.message() : detail;
        }
    }

    /** Builds a rejection carrying the reason's own message. */
    static ModificationResult rejected(RejectionReason reason) {
        return new Rejected(reason, reason.message());
    }

    /** Did the modification go through? */
    default boolean isModified() {
        return this instanceof Modified;
    }

    /** The current booking after a successful modification. */
    default Optional<Booking> modifiedBooking() {
        return this instanceof Modified modified ? Optional.of(modified.booking()) : Optional.empty();
    }

    /** Promotions triggered by the modification, empty otherwise. */
    default List<CancellationResult.Promotion> promotions() {
        return this instanceof Modified modified ? modified.promotions() : List.of();
    }

    /** The rejection reason, when the modification was refused. */
    default Optional<RejectionReason> rejectionReason() {
        return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
    }
}
