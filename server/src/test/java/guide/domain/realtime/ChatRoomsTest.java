package guide.domain.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ChatRooms unit tests — the room/nick vocabulary the handler enforces at
 * the join edge, tested without a socket: closed room vocabulary (alpha/
 * beta, the two-audience isolation lesson), hostile room names refused
 * rather than defaulted, nicks sanitized (control-stripped, trimmed,
 * capped, blank refused), message text truncated not propagated, and the
 * three frame factories carrying type/from/seq exactly as the wire shows
 * them.
 */
class ChatRoomsTest {

    private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void theRoomVocabularyIsExactlyTwoRooms() {
        assertEquals(List.of("alpha", "beta"), ChatRooms.ROOMS,
                "two rooms on purpose — isolation needs two audiences");
    }

    @Test
    void hostileRoomNamesAreRefusedNotRedirected() {
        assertEquals(Optional.of("alpha"), ChatRooms.normalizeRoom("alpha"));
        assertEquals(Optional.of("beta"), ChatRooms.normalizeRoom("  BETA "));
        assertEquals(Optional.empty(), ChatRooms.normalizeRoom("wallstreet"));
        assertEquals(Optional.empty(), ChatRooms.normalizeRoom("alpha beta"));
        assertEquals(Optional.empty(), ChatRooms.normalizeRoom(""));
        assertEquals(Optional.empty(), ChatRooms.normalizeRoom(null));
    }

    @Test
    void nicksAreSanitizedAndBlankOnesAreRefused() {
        assertEquals(Optional.of("ada"), ChatRooms.sanitizeNick(" ada "));
        assertEquals(Optional.of("ada"), ChatRooms.sanitizeNick("a\u0000d\u007Fa"));
        assertEquals(Optional.empty(), ChatRooms.sanitizeNick("   "));
        assertEquals(Optional.empty(), ChatRooms.sanitizeNick("\u0000\u000B"));
        assertEquals(Optional.empty(), ChatRooms.sanitizeNick(null));
        String longNick = "x".repeat(ChatRooms.MAX_NICK_LENGTH + 10);
        assertEquals(ChatRooms.MAX_NICK_LENGTH, ChatRooms.sanitizeNick(longNick).orElseThrow().length());
    }

    @Test
    void textIsTrimmedControlStrippedAndCappedWithAnHonestEllipsis() {
        assertEquals("hello", ChatRooms.sanitizeText(" hello "));
        assertEquals("hello", ChatRooms.sanitizeText("he\u0000llo"));
        assertEquals("", ChatRooms.sanitizeText(null));
        String over = "y".repeat(ChatRooms.MAX_TEXT_LENGTH + 5);
        String capped = ChatRooms.sanitizeText(over);
        assertEquals(ChatRooms.MAX_TEXT_LENGTH, capped.length());
        assertTrue(capped.endsWith("…"), "the cap is visible, not silent");
    }

    @Test
    void theThreeFrameFactoriesCarryTypeFromRoomAndSeq() {
        var join = ChatRooms.joined("ada", "alpha", T0, 7);
        assertEquals("join", join.type());
        assertEquals("system", join.from());
        assertEquals("alpha", join.room());
        assertEquals(7, join.seq());
        assertEquals("leave", ChatRooms.left("ada", "alpha", T0, 8).type());
        assertEquals("say", ChatRooms.said("ada", "alpha", "hi", T0, 9).type());
        assertEquals("ada", ChatRooms.said("ada", "alpha", "hi", T0, 9).from());
    }
}
