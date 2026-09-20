# offheap-gcra

Lock-free, garbage-free rate limiter для Java 21+ на базе алгоритма **GCRA** (Generic Cell Rate Algorithm), с поддержкой двух бэкендов хранения состояния: **на heap** (через `long[]`) и **вне heap** (через `Foreign Function & Memory API`).

---

## Возможности

- **Алгоритм GCRA** — точное управление rate limiting'ом с поддержкой burst tolerance (burst-надбавки).
- **Lock-free архитектура** — нет блокировок (`synchronized`, `ReentrantLock`), только CAS-операции через `VarHandle`; подходит для высококонкурентных нагрузок.
- **Два бэкенда хранения state**:
  - **Heap** — три массива `long[]`, стандартная реализация для быстрого старта.
  - **Off-heap** — три `MemorySegment` через `Arena`; GC не сканирует данные, нулевой давление на old-gen при больших capacity.
- **Автоматическая эвикция неактивных ключей** — daemon-поток очищает слоты, к которым не обращались дольше `evictionTimeout`; поддержка ручной очистки.
- **Пакет `ru.kutkovmax`**, единственная зависимость runtime — только **JDK 21** (библиотека без транзитивных зависимостей).
- **Параметризованные тесты** — один тест-сьют прогоняется сразу на heap и off-heap реализациях (71 тест, 100% покрытие логики).

---

## Требования

- **Java 21** (Temurin / OpenJDK / любая другая сборка LTS)
- Флаг JVM `--enable-preview` (в Java 21 FFM API еще в preview; в Java 22+ вышел из preview, флаг не требуется)
- Maven 3.9+ (в проекте есть Maven Wrapper — `./mvnw` / `mvnw.cmd`)

---

## Быстрый старт

```java
import ru.kutkovmax.LockFreeGcraLimiter;

public class Demo {
    public static void main(String[] args) {
        // 1024 слота, 100 наносекунд между запросами, 200 наносекунд burst tolerance
        try (var limiter = LockFreeGcraLimiter.offHeap(1024, 100, 200)) {
            long key = 42L;
            boolean ok1 = limiter.tryAcquire(key); // true
            boolean ok2 = limiter.tryAcquire(key); // true (burst)
            boolean ok3 = limiter.tryAcquire(key); // false (исчерпан лимит)
        } // Arena автоматически закрывается
    }
}
```

---

## API: `LockFreeGcraLimiter`

### Статические фабрики (выбор бэкенда)

| Метод                                                                                                     | Бэкенд   |
|-----------------------------------------------------------------------------------------------------------|----------|
| `offHeap(int capacity, long interval, long burstTolerance)`                                               | Off-heap |
| `offHeap(int capacity, long interval, long burstTolerance, long evictionTimeout)`                        | Off-heap |
| `heap(int capacity, long interval, long burstTolerance)`                                                  | Heap     |
| `heap(int capacity, long interval, long burstTolerance, long evictionTimeout)`                           | Heap     |
| `new LockFreeGcraLimiter(int capacity, long interval, long burstTolerance)`                              | Off-heap (по умолчанию) |
| `new LockFreeGcraLimiter(int capacity, long interval, long burstTolerance, long evictionTimeout)`        | Off-heap (по умолчанию) |

### Основные методы

| Метод                           | Описание                                                                 |
|---------------------------------|--------------------------------------------------------------------------|
| `boolean tryAcquire(long key)`   | Попытка захвата токена (время из `TimeProvider`). Возвращает `true`/`false`. |
| `boolean tryAcquire(long key, long now)` | То же самое, но с явно переданным монотонным временем (для тестов/детерминизма). |
| `void clean(long now)`           | Ручной запуск эвикции слотов по порогу `evictionTimeout`.             |
| `void close()`                   | Останавливает cleaner-поток и (для off-heap) освобождает `Arena`. Обязательно вызывать в try-with-resources. |

### Параметры

| Параметр             | Ед. изм. | Описание                                                                 |
|----------------------|----------|--------------------------------------------------------------------------|
| `capacity`           | шт       | Максимальное число разных ключей. Обязательно **степень двойки**.     |
| `interval`           | наносек. | Минимальный интервал между двумя последовательными `tryAcquire` одного ключа. |
| `burstTolerance`     | наносек. | Максимальный burst: насколько можно "взять вперед" при неактивном ключе. `= (burstCount - 1) * interval`. |
| `evictionTimeout`    | наносек. | Время неактивности ключа, после которого слот очищается. По умолчанию 60 000 (60 мкс). |

---

## Устройство хранилища

Общая схема для обеих реализаций — **три параллельных массива одинакового размера `capacity`**:

| Массив       | Тип   | Назначение                                                                 |
|--------------|-------|----------------------------------------------------------------------------|
| `keys[]`     | long  | Идентификатор ключа, которому принадлежит слот.                          |
| `cells[]`    | long  | 64-битная ячейка состояния слота (поля ниже).                           |
| `lastAccess` | long  | Монотонное время последнего успешного acquire (для эвикции).             |

### Разметка 64-битной `cell` ([GcraCell.java](src/main/java/ru/kutkovmax/GcraCell.java))

```
[63..62]  STATE  (2 бита)  — 0 EMPTY, 1 CLAIMING, 2 OCCUPIED, 3 EVICTING
[61..0 ]  TAT    (62 бита) — Theoretical Arrival Time в наносекундах (монотонное, zero-anchored, хватает на 146 лет)
```

- `pack(int state, long tat) -> long`
- `state(long cell) -> int`
- `tat(long cell) -> long`

### Жизненный цикл слота

```
EMPTY ───CAS───> CLAIMING ───volatile───> OCCUPIED
  ^                                      │
  │            lastAccess too old        │
  └───────────volatile─────────────────── EVICTING <─CAS re-check─┘
                        CAS rollback if concurrent acquire
```

### Off-heap детали

- Выделение через `Arena.ofShared()` (потокобезопасный доступ)
- Три непрерывных региона по `capacity * 8` байт каждый
- VarHandle через `MethodHandles.memorySegmentViewVarHandle(JAVA_LONG_UNALIGNED)`
- При `close()` — `arena.close()` возвращает всю память ОС сразу

---

## Сборка и тестирование

```bash
# Собрать и прогнать все тесты
./mvnw test        # Linux/macOS
mvnw.cmd test      # Windows

# Упаковать в jar
./mvnw package
```

Тестовый набор: 71 тест.

| Класс теста                  | Штук | Описание                                                    |
|------------------------------|------|-------------------------------------------------------------|
| `GcraCellTest`               | 4    | Упаковка/распаковка 62-битного TAT и состояний.           |
| `GcraLimiterTest`            | 13   | Реверентная synchronized-реализация (корректность GCRA).  |
| `GcraTableTest` × 2 бэкенда  | 18   | Claim / find / collisions / concurrent claims / GCRA.     |
| `LockFreeGcraLimiterTest` ×2 | 36   | Полный сценарий: интервалы, burst, эвикция, конкурентность. |

Все table/limiter-тесты запускаются дважды — для Heap и OffHeap реализаций через `@ParameterizedTest`.

---

## Структура модуля

```
src/main/java/ru/kutkovmax/
├── GcraCell.java                    # Разметка 64-битной ячейки (state + TAT)
├── GcraTable.java                   # Публичный интерфейс хранилища
├── HeapGcraTable.java               # Heap-реализация (long[] + VarHandle)
├── OffHeapGcraTable.java            # Off-heap реализация (Arena + MemorySegment)
├── LockFreeGcraLimiter.java         # Публичный API rate limiter-а
├── GcraLimiter.java                 # Реверентная синхронная реализация GCRA
└── TimeProvider.java                # Zero-anchored монотонный таймер

src/test/java/ru/kutkovmax/
├── GcraCellTest.java
├── GcraLimiterTest.java
├── GcraTableTest.java               # Параметризованные (heap + offHeap)
└── LockFreeGcraLimiterTest.java     # Параметризованные (heap + offHeap)
```

---

## Когда какой бэкенд брать?

| Бэкенд   | Когда использовать                                                                |
|----------|-----------------------------------------------------------------------------------|
| **Heap** | Быстрый старт, capacity < 100k, нет строгих latency-требований, JDK < 21 fallback |
| **Off-heap** | Большие capacity (1M+ слотов), критичность GC-pause p999, персистентность в будущем |

---

## Перспективы

- `FileChannel.map()` backed MemorySegment — сохранение state между рестартами
- Структурированный `StructLayout` для keys/cells/lastAccess в одном непрерывном регионе
- JMH-benchmark: heap vs off-heap latency/throughput
- Утилита `asMicros()` / `asMillis()` для удобного задания интервалов
