package ru.kutkovmax;

final class GcraCell {

    static final int EMPTY = 0;
    static final int CLAIMING = 1;
    static final int OCCUPIED = 2;
    static final int EVICTING = 3;

    private static final int STATE_BITS = 2;
    private static final int TAT_BITS = 62;

    private static final long TAT_MASK = (1L << TAT_BITS) - 1;
    private static final long STATE_MASK = (1L << STATE_BITS) - 1;

    private static final int TAT_SHIFT = 0;
    private static final int STATE_SHIFT = TAT_BITS;

    static long pack(int state, long tat) {
        if ((tat & ~TAT_MASK) != 0) {
            throw new IllegalArgumentException("tat does not fit into 62 bits");
        }
        if ((state & ~STATE_MASK) != 0) {
            throw new IllegalArgumentException("state does not fit into 2 bits");
        }
        return ((long) state << STATE_SHIFT) | (tat & TAT_MASK);
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
