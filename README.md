# offheap-gcra

[![Maven Central](https://img.shields.io/maven-central/v/ru.kutkovmax/offheap-gcra.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/ru.kutkovmax/offheap-gcra)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java 22+](https://img.shields.io/badge/Java-22+-orange.svg)](https://openjdk.java.net/)

Rate limiting библиотека для Java 22+, предлагающая альтернативный подход к учету частоты запросов в высоконагруженных сценариях. В основе проекта лежит алгоритм **GCRA** (Generic Cell Rate Algorithm) и хранение состояния в плоских буферах фиксированного размера без создания объектов в рантайме.

Поддерживаются две реализации: массивы примитивов в куче (`long[]`) и размещение в нативной памяти вне кучи JVM с использованием Foreign Function & Memory API. Вся синхронизация реализована неблокирующим образом (lock-free) через атомарные операции над 64-битными примитивами. Проект не имеет внешних runtime-зависимостей и требует только стандартную библиотеку JDK 22.

## Подключение

Библиотека опубликована в Maven Central. Для добавления в проект используйте следующий фрагмент `pom.xml`:

```xml
<dependency>
    <groupId>ru.kutkovmax</groupId>
    <artifactId>offheap-gcra</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Быстрый старт

API спроектирован так, чтобы скрыть всю низкоуровневую работу с памятью. Для простого rate-limit'а достаточно пары строк:

```java
import ru.kutkovmax.LockFreeGcraLimiter;
import java.time.Duration;

// 1. Создаем лимитер: емкость 1024 ключа, 100 запросов в сек (интервал 10мс), пачка до 5 запросов
try (var limiter = LockFreeGcraLimiter.offHeap(1024, Duration.ofMillis(10), Duration.ofMillis(50))) {
    
    long clientId = 12345L;
    
    // 2. Проверяем лимит (tryAcquire вернет true, если запрос разрешен)
    if (limiter.tryAcquire(clientId)) {
        // Лимит не превышен, выполняем операцию
    } else {
        // Лимит превышен
    }
}
```

## Чем этот проект отличается

Традиционные решения для rate limiting в экосистеме Java обычно хранят состояние каждого клиента в виде объектов внутри ConcurrentHashMap. 
Эта библиотека отказывается от ссылочных структур и объектов-оберток в пользу фиксированной таблицы открытой адресации. 
Размер одного слота в таблице составляет ровно 24 байта, распределенных по трем непрерывным параллельным буферам. Это позволяет:
1. **Снизить нагрузку на GC**: Отсутствие миллионов объектов-нод и заголовков разгружает сборщик мусора при сканировании графа живых ссылок.
2. **Снизить memory footprint**: Данные не разрастаются до 150–200 байт на каждый ключ (однако стоит учитывать пустующие слоты из-за load factor).
3. **Вынести стейт в Off-heap**: При использовании FFM API память вообще не попадает в кучу, сохраняя компактный размер heap dump-а.

Важный нюанс: таблица имеет фиксированный размер (capacity) и не умеет автоматически расширяться при заполнении. Поэтому максимальное количество одновременных ключей нужно закладывать с запасом еще на этапе создания лимитера.

## Сценарии применения

Подобная архитектура востребована в системах, где:
* **Защита API Gateway (IP Rate Limiting):** На шлюз обрушивается трафик с миллионов уникальных IP-адресов. Обычная мапа с объектами быстро приведет к долгим паузам GC (или OOM), в то время как flat-структура стабильно держит стейт в Off-heap.
* **Low-latency компоненты:** Системы, критичные к аллокациям (например, RTB-биддеры или рекламные сети), где летит огромный трафик, а аллокация объектов на горячем пути (hot path) недопустима.
* **Изоляция больших объемов состояния:** Разгрузка профиля приложения при локальном кэшировании лимитов для сотен тысяч активных сессий IoT-устройств.

## Алгоритм GCRA и математическая модель

В отличие от классического Token Bucket, где требуется вычислять начисление токенов, алгоритм Generic Cell Rate Algorithm (GCRA) сводит все состояние лимитера для конкретного ключа к одной точке во времени — **TAT** (Theoretical Arrival Time, теоретическое время прибытия следующего запроса).

Поведение алгоритма задается двумя величинами:
- $T$ (`interval`) — расчетный интервал между запросами при равномерной нагрузке.
- $\tau$ (`burstTolerance`) — допустимый всплеск (толерантность к пачкам).

Вместимость пачки $B$ в привычных терминах Token Bucket определяется отношением толерантности к интервалу:

$$B = \left\lfloor \frac{\tau}{T} \right\rfloor + 1$$

Когда запрос поступает в физический момент времени $t$, лимитер сравнивает текущее время с сохраненным значением $\text{TAT}$:

$$t < \text{TAT} - \tau$$

Если это неравенство истинно, лимит считается превышенным, запрос отклоняется, а $\text{TAT}$ остается неизменным. 
Если же текущее время удовлетворяет допустимому окну ($t \ge \text{TAT} - \tau$), запрос разрешается, а новое значение $\text{TAT}_{\text{new}}$ сдвигается вперед:

$$\text{TAT}_{\text{new}} = \max(\text{TAT}, t) + T$$

Для lock-free реализации такая модель удобна тем, что всё состояние ключа представляет собой одно число. Переход от предыдущего состояния к следующему вычисляется тривиальной арифметикой и фиксируется одной инструкцией CAS без использования мьютексов.

## Структура памяти и упаковка данных

Хранилище организовано как хеш-таблица с открытой адресацией размером `capacity` (степень двойки). 

### Разметка 64-битной ячейки

Чтобы обновление статуса слота и времени TAT выполнялось неделимо, оба значения упакованы в одно 64-битное слово `long`:

```
 63   61 60                                                           0
+-------+-------------------------------------------------------------+
| STATE |                Theoretical Arrival Time (TAT)               |
+-------+-------------------------------------------------------------+
 3 бита                             61 бит
```

Три старших бита кодируют фазу жизненного цикла ячейки (`EMPTY`, `CLAIMING`, `OCCUPIED`, `EVICTING`, `TOMBSTONE`). Младшие 61 бит отведены под монотонное время в наносекундах (`System.nanoTime()`).

Поиск слота выполняется линейным пробингом по маске хеша. При удалении записей слот переводится в `TOMBSTONE`, чтобы не разорвать цепочку коллизий.
*Примечание: Как и в любой хеш-таблице с открытой адресацией, при высоком заполнении (load factor > 70%) производительность может деградировать из-за длинных цепочек пробинга. Рекомендуется закладывать `capacity` с запасом.*

## Результат операции

Метод `acquire` возвращает перечисление `AcquireResult`:
- `ACQUIRED` — запрос разрешен.
- `RATE_LIMITED` — лимит частоты превышен клиентом (ожидается HTTP 429).
- `CAPACITY_EXHAUSTED` — таблица переполнена, свободные слоты отсутствуют (ожидается HTTP 503 или fallback).

## Примеры использования

Пример интеграции в качестве Servlet Filter для защиты API по IP-адресам:

```java
import ru.kutkovmax.AcquireResult;
import ru.kutkovmax.LockFreeGcraLimiter;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Duration;

public class IpRateLimitFilter implements Filter {
    
    // Емкость: 65 536 ключей. Лимит: 50 запросов в секунду (интервал 20 мс), пачка до 5 запросов
    private final LockFreeGcraLimiter limiter = LockFreeGcraLimiter.offHeap(
            65_536,
            Duration.ofMillis(20),
            Duration.ofMillis(100),
            Duration.ofSeconds(60)
    );

    @Override
    public void init(FilterConfig filterConfig) {
        // Запускаем фоновую очистку устаревших сессий
        limiter.scheduleEviction(Duration.ofSeconds(10));
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
            
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        // В реальном приложении IP стоит получать с учетом X-Forwarded-For
        String ip = httpRequest.getRemoteAddr();
        long key = ipToLong(ip); // Конвертируем IPv4 в long

        AcquireResult result = limiter.acquire(key);
        
        switch (result) {
            case ACQUIRED -> chain.doFilter(request, response);
            case RATE_LIMITED -> httpResponse.sendError(429, "Too Many Requests");
            case CAPACITY_EXHAUSTED -> {
                // Сервер перегружен: кончились слоты для новых IP
                filterConfig.getServletContext().log("RateLimiter capacity exhausted!");
                httpResponse.sendError(503, "Service Unavailable");
            }
        }
    }

    @Override
    public void destroy() {
        limiter.close();
    }
}
```

## Сравнение Heap и Off-Heap

Проект включает бенчмарк `HeapVsOffHeapComparison` на емкости в 65 536 слотов (Adoptium Temurin 22.0.2, Windows):

| Бэкенд | Нагрузка | Потоки | Throughput | p50 | p99 | p99.9 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **HEAP** | 1 ключ (single-thread) | 1 | 8.4 млн op/s | 100 ns | 1.0 мкс | 4.2 мкс |
| **OFF-HEAP** | 1 ключ (single-thread) | 1 | 6.5 млн op/s | 100 ns | 0.3 мкс | 1.4 мкс |
| **HEAP** | 10 000 разных ключей | 16 | **23.9 млн op/s** | 100 ns | 0.7 мкс | 2.6 мкс |
| **OFF-HEAP** | 10 000 разных ключей | 16 | **20.1 млн op/s** | 100 ns | 0.7 мкс | 2.7 мкс |

Куча (`long[]`) опережает off-heap на 10–15% по абсолютной пропускной способности из-за оптимизаций JIT для плоских массивов. 
Смысл off-heap варианта заключается в изоляции стейта: вынесение данных за пределы кучи минимизирует время пауз GC при миллионах активных ключей.

## Тестирование и верификация

Надежность lock-free алгоритма доказана на нескольких уровнях:
- Набор из 114 модульных тестов на JUnit 5.
- Стресс-тесты конкурентности на базе **JCStress**, подтверждающие отсутствие потери обновлений при CAS-операциях и корректность параллельной эвикции.
- Бенчмарки на базе JMH.

## Требования

Для сборки и работы необходимы Java 22 или выше (JEP 454) и Maven 3.8+. Библиотека распространяется под лицензией Apache License 2.0.
