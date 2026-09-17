package ru.kutkovmax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                GcraCell.EVICTING,
                (1L << 62) - 1
        );

        assertEquals(GcraCell.EVICTING, GcraCell.state(cell));
        assertEquals((1L << 62) - 1, GcraCell.tat(cell));
    }

    @Test
    void differentStatesDoNotAffectTat() {
        long tat = 123_456;

        for (int state = 0; state < 4; state++) {
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
}
