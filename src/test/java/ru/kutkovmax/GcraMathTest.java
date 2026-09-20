package ru.kutkovmax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GcraMathTest {

    @Test
    void addTatRejectsOverflowPast62Bits() {
        assertThrows(ArithmeticException.class, () -> GcraMath.addTat(GcraCell.MAX_TAT, 1));
        assertThrows(ArithmeticException.class, () -> GcraMath.addTat(GcraCell.MAX_TAT, GcraCell.MAX_TAT));
    }

    @Test
    void addTatRejectsNegativeOperands() {
        assertThrows(ArithmeticException.class, () -> GcraMath.addTat(-1, 1));
        assertThrows(ArithmeticException.class, () -> GcraMath.addTat(1, -1));
    }

    @Test
    void nextTatUsesMaxOfCurrentAndNow() {
        assertEquals(150, GcraMath.nextTat(100, 50, 50));
        assertEquals(150, GcraMath.nextTat(50, 100, 50));
    }

    @Test
    void nextTatRejectsNowOutsideTatRange() {
        assertThrows(ArithmeticException.class, () -> GcraMath.nextTat(0, -1, 1));
        assertThrows(ArithmeticException.class, () -> GcraMath.nextTat(0, GcraCell.MAX_TAT + 1, 1));
    }

    @Test
    void newKeyTatIsNowPlusInterval() {
        assertEquals(100, GcraMath.newKeyTat(0, 100));
        assertThrows(ArithmeticException.class, () -> GcraMath.newKeyTat(GcraCell.MAX_TAT, 1));
    }
}
