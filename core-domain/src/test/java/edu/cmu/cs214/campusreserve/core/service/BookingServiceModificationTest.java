package edu.cmu.cs214.campusreserve.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.BookingModification;
import edu.cmu.cs214.campusreserve.core.domain.BookingModificationRequest;
import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import edu.cmu.cs214.campusreserve.core.policy.BookingPolicy;
import edu.cmu.cs214.campusreserve.core.support.Fixtures;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class BookingServiceModificationTest extends BookingServiceTestBase {

    private static final TimeInterval TEN_TO_ELEVEN = Fixtures.interval("10:00", "11:00");

    @Test
    void shortensLengthensAndMovesABookingWhileKeepingItsIdentityAndRoom() {
        String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);

        Booking shortened = modifyInterval(id, Fixtures.interval("10:00", "10:30"));
        Booking lengthened = modifyInterval(id, Fixtures.interval("10:00", "11:30"));
        Booking moved = modifyInterval(id, Fixtures.interval("10:15", "11:45"));

        assertEquals(id, moved.id());
        assertEquals("r-1", moved.roomId());
        assertEquals(Fixtures.interval("10:00", "10:30"), shortened.interval());
        assertEquals(Fixtures.interval("10:00", "11:30"), lengthened.interval());
        assertEquals(Fixtures.interval("10:15", "11:45"), moved.interval());
        assertEquals(3, moved.modificationHistory().size());
    }

    @Test
    void changesAttendeesAndRecordsOldAndNewDetails() {
        String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);

        Booking modified = service.modifyBooking(
            BookingModificationRequest.attendeesOnly(id, "u-1", 7))
            .modifiedBooking().orElseThrow();

        assertEquals(7, modified.attendees());
        BookingModification history = modified.modificationHistory().get(0);
        assertEquals(TEN_TO_ELEVEN, history.oldInterval());
        assertEquals(4, history.oldAttendees());
        assertEquals(TEN_TO_ELEVEN, history.newInterval());
        assertEquals(7, history.newAttendees());
        assertEquals(clock.instant(), history.modifiedAt());
    }

    @Test
    void aNoOpSucceedsWithoutAWriteHistoryEventOrPromotionPass() {
        String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
        book("r-1", "u-2", TEN_TO_ELEVEN);
        int eventsBefore = publisher.size();

        ModificationResult result = service.modifyBooking(
            BookingModificationRequest.unchanged(id, "u-1"));

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        assertTrue(result.modifiedBooking().orElseThrow().modificationHistory().isEmpty());
        assertEquals(eventsBefore, publisher.size());
        assertEquals(1, waitlist.count());
    }

    @Test
    void rejectsMissingForeignCancelledAndStartedBookingsWithoutMutation() {
        assertEquals(Optional.of(RejectionReason.BOOKING_NOT_FOUND), service.modifyBooking(
            BookingModificationRequest.attendeesOnly("bk-missing", "u-1", 5)).rejectionReason());

        String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
        assertRejectedUnchanged(id, RejectionReason.NOT_BOOKING_OWNER,
            () -> BookingModificationRequest.attendeesOnly(id, "u-2", 5));

        service.cancelBooking(id, "u-1");
        assertRejectedUnchanged(id, RejectionReason.BOOKING_ALREADY_CANCELLED,
            () -> BookingModificationRequest.attendeesOnly(id, "u-1", 5));

        String later = bookAndGetId(
            "r-1", "u-2", Fixtures.intervalOn("2026-10-22", "10:00", "11:00"));
        clock.setTo("2026-10-22T10:00:00Z");
        assertRejectedUnchanged(later, RejectionReason.BOOKING_ALREADY_STARTED,
            () -> BookingModificationRequest.attendeesOnly(later, "u-2", 5));
    }

    @Test
    void everyRequestRuleRejectionLeavesTheOriginalUnchanged() {
        String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);

        assertRejectedUnchanged(id, RejectionReason.INTERVAL_TOO_SHORT,
            () -> BookingModificationRequest.intervalOnly(
                id, "u-1", Fixtures.interval("10:00", "10:15")));
        assertRejectedUnchanged(id, RejectionReason.INTERVAL_TOO_LONG,
            () -> BookingModificationRequest.intervalOnly(
                id, "u-1", Fixtures.interval("10:00", "15:00")));
        assertRejectedUnchanged(id, RejectionReason.INTERVAL_NOT_ALIGNED,
            () -> BookingModificationRequest.intervalOnly(
                id, "u-1", Fixtures.interval("10:05", "11:05")));
        assertRejectedUnchanged(id, RejectionReason.INTERVAL_SPANS_DAYS,
            () -> BookingModificationRequest.intervalOnly(id, "u-1", TimeInterval.parse(
                "2026-10-20T23:00:00Z", "2026-10-21T01:00:00Z")));
        assertRejectedUnchanged(id, RejectionReason.INTERVAL_IN_PAST,
            () -> BookingModificationRequest.intervalOnly(id, "u-1", TimeInterval.parse(
                "2026-10-19T08:00:00Z", "2026-10-19T09:00:00Z")));
        assertRejectedUnchanged(id, RejectionReason.OUTSIDE_OPENING_HOURS,
            () -> BookingModificationRequest.intervalOnly(id, "u-1", TimeInterval.parse(
                "2026-10-20T05:00:00Z", "2026-10-20T06:00:00Z")));
        assertRejectedUnchanged(id, RejectionReason.TOO_FAR_IN_ADVANCE,
            () -> BookingModificationRequest.intervalOnly(id, "u-1", TimeInterval.parse(
                "2027-01-20T10:00:00Z", "2027-01-20T11:00:00Z")));
        assertRejectedUnchanged(id, RejectionReason.ATTENDEES_EXCEED_CAPACITY,
            () -> BookingModificationRequest.attendeesOnly(id, "u-1", 11));

        addRoom(Fixtures.studyRoom("r-1", 10).withActive(false));
        assertRejectedUnchanged(id, RejectionReason.ROOM_INACTIVE,
            () -> BookingModificationRequest.attendeesOnly(id, "u-1", 5));
    }

    @Test
    void ignoresItselfButRejectsRealRoomAndOwnerCalendarConflicts() {
        String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);

        assertTrue(service.modifyBooking(BookingModificationRequest.intervalOnly(
            id, "u-1", Fixtures.interval("10:15", "11:15"))).isModified());

        assertTrue(book("r-1", "u-2", Fixtures.interval("12:00", "13:00")).isConfirmed());
        assertRejectedUnchanged(id, RejectionReason.ROOM_UNAVAILABLE,
            () -> BookingModificationRequest.intervalOnly(
                id, "u-1", Fixtures.interval("12:15", "13:15")));

        assertTrue(book("r-2", "u-1", Fixtures.interval("14:00", "15:00")).isConfirmed());
        assertRejectedUnchanged(id, RejectionReason.USER_DOUBLE_BOOKED,
            () -> BookingModificationRequest.intervalOnly(
                id, "u-1", Fixtures.interval("14:15", "15:15")));
    }

    @Test
    void modificationWaivesTheActiveBookingCap() {
        build(BookingPolicy.defaults().withMaxActiveBookingsPerUser(1));
        String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);

        ModificationResult result = service.modifyBooking(
            BookingModificationRequest.attendeesOnly(id, "u-1", 5));

        assertTrue(result.isModified());
        assertEquals(5, result.modifiedBooking().orElseThrow().attendees());
    }

    @Test
    void shorteningPromotesInArrivalOrderAndRechecksTheWaitersCalendar() {
        String held = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "12:00"));
        book("r-1", "u-2", Fixtures.interval("11:00", "12:00"));
        book("r-1", "u-3", Fixtures.interval("11:00", "12:00"));
        assertTrue(book("r-2", "u-2", Fixtures.interval("11:00", "12:00")).isConfirmed());

        ModificationResult result = service.modifyBooking(
            BookingModificationRequest.intervalOnly(
                held, "u-1", Fixtures.interval("10:00", "11:00")));

        assertEquals(1, result.promotions().size());
        assertEquals("u-3", result.promotions().get(0).booking().userId());
        assertEquals(List.of("u-2"), service.waitlistForRoom("r-1").stream()
            .map(entry -> entry.userId()).toList());
    }

    @Test
    void attendeeOnlyChangeDoesNotRunPromotion() {
        String old = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
        book("r-1", "u-2", TEN_TO_ELEVEN);
        String future = bookAndGetId(
            "r-1", "u-3", Fixtures.intervalOn("2026-10-22", "10:00", "11:00"));
        clock.setTo("2026-10-21T09:00:00Z");

        ModificationResult result = service.modifyBooking(
            BookingModificationRequest.attendeesOnly(future, "u-3", 5));

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        assertEquals(1, waitlist.count());
        assertTrue(service.findBooking(old).orElseThrow().holdsRoom());
    }

    @Test
    void lengtheningRunsPromotionPassEvenWhenItFreesNoTime() {
        bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
        book("r-1", "u-2", TEN_TO_ELEVEN);
        String future = bookAndGetId(
            "r-1", "u-3", Fixtures.intervalOn("2026-10-22", "10:00", "11:00"));
        clock.setTo("2026-10-21T09:00:00Z");

        ModificationResult result = service.modifyBooking(
            BookingModificationRequest.intervalOnly(future, "u-3",
                Fixtures.intervalOn("2026-10-22", "10:00", "11:30")));

        assertTrue(result.promotions().isEmpty());
        assertEquals(0, waitlist.count());
    }

    private Booking modifyInterval(String bookingId, TimeInterval interval) {
        return service.modifyBooking(BookingModificationRequest.intervalOnly(
            bookingId, "u-1", interval)).modifiedBooking().orElseThrow();
    }

    private void assertRejectedUnchanged(
        String bookingId,
        RejectionReason reason,
        Supplier<BookingModificationRequest> request) {
        Booking before = service.findBooking(bookingId).orElseThrow();
        int eventCount = publisher.size();

        ModificationResult result = service.modifyBooking(request.get());

        assertEquals(Optional.of(reason), result.rejectionReason());
        assertEquals(before, service.findBooking(bookingId).orElseThrow());
        assertEquals(eventCount, publisher.size());
    }
}
