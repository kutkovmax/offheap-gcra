package ru.kutkovmax;

final class GcraCell {

    private static final int TAT_BITS = 30;
    private static final int STATE_BITS = 2;

    private static final long TAT_MASK = (1L << TAT_BITS) - 1;
    private static final long STATE_MASK = (1L << STATE_BITS) - 1;

    private static final int STATE_SHIFT = TAT_BITS;
    private static final int VERSION_SHIFT = TAT_BITS + STATE_BITS;

    static long pack(int version, int state, long tat) {
        if ((tat & ~TAT_MASK) != 0) {
            throw new IllegalArgumentException("tat does not fit into 30 bits");
        }

        if ((state & ~STATE_MASK) != 0) {
            throw new IllegalArgumentException("state does not fit into 2 bits");
        }

        return ((long) version << VERSION_SHIFT)
                | ((long) state << STATE_SHIFT)
                | tat;
    }

    static int version(long cell) {
        return (int) (cell >>> VERSION_SHIFT);
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