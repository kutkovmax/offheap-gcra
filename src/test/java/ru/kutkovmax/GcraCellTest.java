package ru.kutkovmax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GcraCellTest {

    @Test
    void packsAndUnpacks() {
        long cell = GcraCell.pack(
                GcraCell.OCCUPIED,
                123_456
        );

        assertEquals(GcraCell.OCCUPIED, GcraCell.state(cell));
        assertEquals(123_456, GcraCell.tat(cell));
    }

    @Test
    void supportsMaximumValues() {
        long cell = GcraCell.pack(
                GcraCell.TOMBSTONE,
                (1L << 61) - 1
        );

        assertEquals(GcraCell.TOMBSTONE, GcraCell.state(cell));
        assertEquals((1L << 61) - 1, GcraCell.tat(cell));
    }

    @Test
    void differentStatesDoNotAffectTat() {
        long tat = 123_456;

        for (int state = 0; state <= 4; state++) {
            long cell = GcraCell.pack(state, tat);

            assertEquals(tat, GcraCell.tat(cell));
            assertEquals(state, GcraCell.state(cell));
        }
    }

    @Test
    void defaultEmptyCellIsZero() {
        long empty = GcraCell.pack(GcraCell.EMPTY, 0);
        assertEquals(0, empty);
    }

    @Test
    void rejectsNegativeTat() {
        assertThrows(IllegalArgumentException.class, () -> GcraCell.pack(GcraCell.OCCUPIED, -1));
    }

    @Test
    void rejectsTatAbove61Bits() {
        assertThrows(IllegalArgumentException.class, () -> GcraCell.pack(GcraCell.OCCUPIED, GcraCell.MAX_TAT + 1));
    }

    @Test
    void rejectsInvalidState() {
        assertThrows(IllegalArgumentException.class, () -> GcraCell.pack(8, 0));
        assertThrows(IllegalArgumentException.class, () -> GcraCell.pack(-1, 0));
    }

    @Test
    void namedBitConstantsMatchPackedLayout() {
        assertEquals(61, GcraCell.TAT_BITS);
        assertEquals(3, GcraCell.STATE_BITS);
        assertEquals((1L << 61) - 1, GcraCell.TAT_MASK);
        assertEquals(0b111L, GcraCell.STATE_MASK);
        assertEquals(GcraCell.TAT_MASK, GcraCell.MAX_TAT);
    }
}
