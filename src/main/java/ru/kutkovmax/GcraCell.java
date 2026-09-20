package ru.kutkovmax;

final class GcraCell {

    static final int EMPTY = 0;
    static final int CLAIMING = 1;
    static final int OCCUPIED = 2;
    static final int EVICTING = 3;

    static final int STATE_BITS = 2;
    static final int TAT_BITS = 62;

    static final long TAT_MASK = (1L << TAT_BITS) - 1;
    static final long STATE_MASK = (1L << STATE_BITS) - 1;
    static final long MAX_TAT = TAT_MASK;

    private static final int STATE_SHIFT = TAT_BITS;

    static long pack(int state, long tat) {
        if (tat < 0 || tat > MAX_TAT) {
            throw new IllegalArgumentException("tat does not fit into 62 bits");
        }
        if ((state & ~STATE_MASK) != 0) {
            throw new IllegalArgumentException("state does not fit into 2 bits");
        }
        return ((long) state << STATE_SHIFT) | tat;
    }

    static int state(long cell) {
        return (int) ((cell >>> STATE_SHIFT) & STATE_MASK);
    }

    static long tat(long cell) {
        return cell & TAT_MASK;
    }

    private GcraCell() {
    }
}
