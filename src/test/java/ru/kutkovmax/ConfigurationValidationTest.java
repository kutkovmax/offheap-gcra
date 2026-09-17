package ru.kutkovmax;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigurationValidationTest {

    static Stream<Arguments> factories() {
        return Stream.of(
                Arguments.of("heap", LockFreeGcraLimiter.HEAP_FACTORY),
                Arguments.of("offHeap", LockFreeGcraLimiter.OFF_HEAP_FACTORY)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void rejectsZeroCapacity(String name, LockFreeGcraLimiter.TableFactory factory) {
        assertThrows(IllegalArgumentException.class, () -> factory.create(0, 100, 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> createLimiter(factory, 0, 100, 0, 1_000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void rejectsNegativeCapacity(String name, LockFreeGcraLimiter.TableFactory factory) {
        assertThrows(IllegalArgumentException.class, () -> factory.create(-1, 100, 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> createLimiter(factory, -1, 100, 0, 1_000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void rejectsNonPowerOfTwoCapacity(String name, LockFreeGcraLimiter.TableFactory factory) {
        assertThrows(IllegalArgumentException.class, () -> factory.create(10, 100, 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> factory.create(3, 100, 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> createLimiter(factory, 12, 100, 0, 1_000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void rejectsZeroAndNegativeInterval(String name, LockFreeGcraLimiter.TableFactory factory) {
        assertThrows(IllegalArgumentException.class, () -> factory.create(16, 0, 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> factory.create(16, -1, 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> createLimiter(factory, 16, 0, 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> createLimiter(factory, 16, -5, 0, 1_000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void rejectsNegativeBurstTolerance(String name, LockFreeGcraLimiter.TableFactory factory) {
        assertThrows(IllegalArgumentException.class, () -> factory.create(16, 100, -1, 1_000));
        assertThrows(IllegalArgumentException.class, () -> createLimiter(factory, 16, 100, -1, 1_000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void rejectsZeroAndNegativeEvictionTimeout(String name, LockFreeGcraLimiter.TableFactory factory) {
        assertThrows(IllegalArgumentException.class, () -> factory.create(16, 100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> factory.create(16, 100, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> createLimiter(factory, 16, 100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> createLimiter(factory, 16, 100, 0, -10));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void rejectsValuesThatWouldOverflowTat(String name, LockFreeGcraLimiter.TableFactory factory) {
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(16, GcraCell.MAX_TAT + 1, 0, 1_000));
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(16, 100, GcraCell.MAX_TAT + 1, 1_000));
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(16, 100, 0, GcraCell.MAX_TAT + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> createLimiter(factory, 16, GcraCell.MAX_TAT + 1, 0, 1_000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void acceptsBoundaryValidConfiguration(String name, LockFreeGcraLimiter.TableFactory factory) {
        assertDoesNotThrow(() -> {
            GcraTable table = factory.create(1, 1, 0, 1);
            table.close();
        });
        assertDoesNotThrow(() -> {
            try (LockFreeGcraLimiter limiter = createLimiter(factory, 2, GcraCell.MAX_TAT, GcraCell.MAX_TAT, GcraCell.MAX_TAT)) {
                limiter.tryAcquire(1, 0);
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void acquireDoesNotWrapTatOnOverflow(String name, LockFreeGcraLimiter.TableFactory factory) {
        try (LockFreeGcraLimiter limiter = createLimiter(factory, 4, GcraCell.MAX_TAT, 0, 1_000)) {
            assertThrows(ArithmeticException.class, () -> limiter.tryAcquire(1, 1));
        }
    }

    @org.junit.jupiter.api.Test
    void rejectsNullDurations() {
        assertThrows(NullPointerException.class, () -> LockFreeGcraLimiter.heap(16, null, java.time.Duration.ofMillis(10)));
        assertThrows(NullPointerException.class, () -> LockFreeGcraLimiter.heap(16, java.time.Duration.ofMillis(10), null));
        assertThrows(NullPointerException.class, () -> LockFreeGcraLimiter.offHeap(16, null, java.time.Duration.ofMillis(10)));
        assertThrows(NullPointerException.class, () -> LockFreeGcraLimiter.offHeap(16, java.time.Duration.ofMillis(10), null));
        assertThrows(NullPointerException.class, () -> new LockFreeGcraLimiter(16, null, java.time.Duration.ofMillis(10)));
    }

    @org.junit.jupiter.api.Test
    void rejectsNegativeAndZeroDurationInterval() {
        assertThrows(IllegalArgumentException.class, () -> LockFreeGcraLimiter.heap(16, java.time.Duration.ZERO, java.time.Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> LockFreeGcraLimiter.heap(16, java.time.Duration.ofMillis(-5), java.time.Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> LockFreeGcraLimiter.offHeap(16, java.time.Duration.ZERO, java.time.Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> LockFreeGcraLimiter.offHeap(16, java.time.Duration.ofMillis(-5), java.time.Duration.ZERO));
    }

    @org.junit.jupiter.api.Test
    void acceptsValidDurationConfigurations() {
        assertDoesNotThrow(() -> {
            try (LockFreeGcraLimiter limiter = LockFreeGcraLimiter.heap(
                    16,
                    java.time.Duration.ofMillis(10),
                    java.time.Duration.ofMillis(50),
                    java.time.Duration.ofSeconds(10)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(limiter.tryAcquire(1));
            }

            try (LockFreeGcraLimiter limiter = LockFreeGcraLimiter.offHeap(
                    16,
                    java.time.Duration.ofMillis(10),
                    java.time.Duration.ofMillis(50),
                    java.time.Duration.ofSeconds(10)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(limiter.tryAcquire(1));
            }

            try (LockFreeGcraLimiter limiter = new LockFreeGcraLimiter(
                    16,
                    java.time.Duration.ofMillis(10),
                    java.time.Duration.ofMillis(50)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(limiter.tryAcquire(1));
            }
        });
    }

    private static LockFreeGcraLimiter createLimiter(
            LockFreeGcraLimiter.TableFactory factory,
            int capacity,
            long interval,
            long burstTolerance,
            long evictionTimeout
    ) {
        return new LockFreeGcraLimiter(factory, capacity, interval, burstTolerance, evictionTimeout);
    }
}
