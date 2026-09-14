package ru.kutkovmax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GcraCellTest {

    @Test
    void packsAndUnpacks() {
        long cell = GcraCell.pack(
                42,
                GcraState.OCCUPIED,
                123_456
        );

        assertEquals(42, GcraCell.version(cell));
        assertEquals(GcraState.OCCUPIED, GcraCell.state(cell));
        assertEquals(123_456, GcraCell.tat(cell));
    }

    @Test
    void supportsMaximumValues() {
        long cell = GcraCell.pack(
                -1,
                GcraState.EVICTING,
                (1L << 30) - 1
        );

        assertEquals(-1, GcraCell.version(cell));
        assertEquals(GcraState.EVICTING, GcraCell.state(cell));
        assertEquals((1L << 30) - 1, GcraCell.tat(cell));
    }

    @Test
    void differentStatesDoNotAffectTat() {
        long tat = 123_456;

        for (int state = 0; state < 4; state++) {
            long cell = GcraCell.pack(10, state, tat);

            assertEquals(tat, GcraCell.tat(cell));
            assertEquals(state, GcraCell.state(cell));
            assertEquals(10, GcraCell.version(cell));
        }
    }

    @Test
    void differentVersionsDoNotAffectLowerBits() {
        long tat = 123_456;

        long first = GcraCell.pack(1, GcraState.OCCUPIED, tat);
        long second = GcraCell.pack(2, GcraState.OCCUPIED, tat);

        assertEquals(tat, GcraCell.tat(first));
        assertEquals(tat, GcraCell.tat(second));
        assertEquals(
                GcraState.OCCUPIED,
                GcraCell.state(first)
        );
        assertEquals(
                GcraState.OCCUPIED,
                GcraCell.state(second)
        );
    }
}