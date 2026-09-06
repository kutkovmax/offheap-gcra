package ru.kutkovmax;

final class GcraState {

    static final int EMPTY = 0;
    static final int CLAIMING = 1;
    static final int OCCUPIED = 2;
    static final int EVICTING = 3;

    private GcraState() {
    }
}