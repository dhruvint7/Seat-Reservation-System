# Seat Reservation at Scale — Engineering Write-up

## 1. Overview

This service implements a concurrency-safe seat reservation system for assigned-seat events.

The primary design goal is correctness under high contention:

- A seat must never be sold twice.
- A user must never exceed the per-show booking limit.
- Retried requests must not create duplicate reservations.
- Multi-seat reservations must be atomic.
- Expected reservation conflicts must return `409`, not `500`.
- Inventory counts must remain internally consistent.
- Cancellation must safely release previously confirmed seats.

The implementation uses **Java 21, Spring Boot, Spring Data JPA, Hibernate, MySQL 8.4 and Liquibase**.

The project also includes **JUnit 5 unit tests, MySQL Testcontainers integration tests and Swagger/OpenAPI documentation**.

MySQL is intentionally used as the source of truth for reservation state and concurrency control.

---

# 2. Reservation Model

The system uses immediate confirmation rather than temporary holds.

The reservation lifecycle is:

```text
AVAILABLE
    │
    │ reserve
    ▼
CONFIRMED
    │
    │ cancel
    ▼
AVAILABLE
```

There is no `HELD` state in this implementation.

This was a deliberate choice to keep the reservation state machine simple and avoid introducing:

- Hold expiry jobs
- Background schedulers
- Hold timeout races
- Additional cleanup logic

A production ticketing system could introduce time-boxed holds if payment authorization requires time between seat selection and payment completion.

---

# 3. Atomic Reservation Mechanism

The reservation operation is executed inside a database transaction.

Conceptually:

```text
BEGIN TRANSACTION
       │
       ▼
Validate request
       │
       ▼
Validate idempotency
       │
       ▼
Lock show-user row
       │
       ▼
Lock requested seats
       │
       ▼
Validate seat availability
       │
       ▼
Validate per-user limit
       │
       ▼
Create reservation
       │
       ▼
Mark seats CONFIRMED
       │
       ▼
Update per-user count
       │
       ▼
Commit
```

The transaction provides a single atomic decision point.

If any validation fails, the transaction does not create a partial reservation.

For a multi-seat request, this means:

```text
A1 AVAILABLE
A2 AVAILABLE
A3 TAKEN
```

A request for:

```text
A1, A2, A3
```

is rejected completely.

A1 and A2 are not reserved independently.

---

# 4. Preventing Double Booking

The primary correctness mechanism is **pessimistic row-level locking**.

The requested seat rows are selected using a write lock.

Conceptually:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
```

The application locks the requested seat rows before validating and modifying their state.

For example, if two transactions concurrently attempt:

```text
User A → A1
User B → A1
```

the transactions cannot both modify A1 simultaneously.

One transaction obtains the row lock and successfully changes:

```text
A1: AVAILABLE → CONFIRMED
```

The other transaction subsequently observes that the seat is no longer available and returns:

```text
409 Conflict
```

Therefore the database transaction, rather than an in-memory Java lock, is responsible for correctness.

This is important because the application may eventually run multiple instances.

An in-memory `synchronized` block would only protect threads inside one JVM and would not provide correctness across multiple application instances.

---

# 5. Why Database Locking Instead of `synchronized`

An initial consideration was to use Java synchronization for concurrent requests.

For example:

```java
synchronized
```

However, that approach is insufficient for a deployed service because:

- Multiple application instances would have independent JVM locks.
- A process restart would remove the lock state.
- Database state could still be modified by another application instance.
- Horizontal scaling would break the single-process synchronization boundary.

Therefore, database-level locking was selected.

The database already owns the inventory state, so the database is also the natural place to enforce concurrent modification correctness.

---

# 6. Multi-seat Deadlock Prevention

Multi-seat reservations introduce a potential deadlock problem.

Consider two concurrent requests:

```text
Transaction A:
A1 → A2

Transaction B:
A2 → A1
```

If each transaction locks its first seat and waits for the second, they can wait on each other.

To avoid this, requested seats are sorted before acquiring locks.

For example:

```text
Input:
A3, A1, A2

Normalized:
A1, A2, A3
```

All transactions therefore acquire seat locks in the same deterministic order.

This significantly reduces deadlock risk caused by inconsistent lock acquisition order.

The same lock-ordering principle is also applied when cancellation acquires its related locks.

---

# 7. Per-user Booking Limit

The default per-user limit is:

```text
4 seats per user per show
```

The system maintains the current count in:

```text
show_users
```

with:

```text
(show_id, user_id, seat_count)
```

Before a reservation is confirmed, the corresponding `show_users` row is locked.

Conceptually:

```text
Current count = 3
Limit = 4
Requested seats = 2
```

The transaction determines:

```text
3 + 2 > 4
```

and rejects the request.

The important part is that the check and update occur while the user/show row is locked.

Therefore two concurrent requests cannot both observe the same stale count and independently exceed the limit.

---

# 8. Idempotency

Reservation requests require an idempotency key.

The system stores:

```text
show_id
user_id
idempotency_key
request_hash
reservation_id
created_at
```

There is a unique constraint on:

```text
(show_id, user_id, idempotency_key)
```

The request body is hashed.

This gives two important behaviors.

## Same key + same request

Example:

```text
key = reserve-123
seats = A1
```

Retrying the same request returns the original reservation instead of creating another one.

This protects against:

- Client retries
- Network timeouts
- Duplicate submissions
- Retransmission after an uncertain response

## Same key + different request

Example:

```text
First request:
key = reserve-123
seat = A1

Second request:
key = reserve-123
seat = A2
```

The request hash differs.

The second request is rejected with:

```text
409 Conflict
```

This prevents accidental or malicious reuse of an idempotency key for a different operation.

---

# 9. Cancellation

The implementation uses explicit cancellation.

The cancellation endpoint is owner-only.

The cancellation flow is:

```text
Lock reservation
       │
       ▼
Verify reservation owner
       │
       ▼
Lock show-user row
       │
       ▼
Lock reservation seats
       │
       ▼
Mark reservation cancelled
       │
       ▼
Release seats
       │
       ▼
Decrease user seat count
       │
       ▼
Commit
```

Cancellation is performed transactionally so that the reservation state, seat state and user count are updated together.

A user cannot cancel another user's reservation because ownership is validated using the authenticated JWT identity.

---

# 10. Authentication and Identity

The user identity is derived from the JWT token.

The reservation request does not accept `userId` as part of the request body.

This prevents a caller from simply changing:

```json
{
  "userId": "another-user"
}
```

to impersonate another user.

The authenticated identity is used for:

- Reservation ownership
- Per-user booking limits
- Idempotency scoping
- Cancellation authorization

The deployed token endpoint is provided only as an evaluation/demo mechanism.

A production deployment would normally integrate with an external identity provider.

---

# 11. Inventory Consistency

The database stores seat-level state as the source of truth.

The show details API exposes:

```text
totalSeats
availableSeats
heldSeats
confirmedSeats
```

The invariant is:

```text
totalSeats =
    availableSeats +
    heldSeats +
    confirmedSeats
```

Because this implementation does not use temporary holds:

```text
heldSeats = 0
```

The seat-level state is used to reconcile the aggregate counts.

This avoids maintaining an independent mutable inventory counter as the only source of truth.

---

# 12. Money Representation

All prices are represented as integer paise.

For example:

```text
₹100 = 10000 paise
```

No floating-point representation is used for reservation amounts.

This avoids rounding and precision issues in monetary calculations.

---

# 13. Database Design

The core tables are:

```text
shows
seats
reservations
reservation_seats
show_users
idempotency_keys
```

## `shows`

Stores show-level configuration:

```text
id
name
price_paise
per_user_limit
created_at
```

## `seats`

Stores the current state of each assigned seat:

```text
show_id
seat_number
status
reservation_id
updated_at
```

Primary key:

```text
(show_id, seat_number)
```

## `reservations`

Stores reservation ownership and lifecycle:

```text
id
show_id
user_id
amount_paise
status
created_at
cancelled_at
```

## `reservation_seats`

Maps reservations to their seats.

## `show_users`

Maintains the per-user seat count for a show.

```text
(show_id, user_id) → seat_count
```

## `idempotency_keys`

Maps an idempotency key to the reservation created by the original request.

---

# 14. Database as the Source of Truth

The system deliberately avoids maintaining independent seat ownership state in:

- Java memory
- Redis
- Application-local caches

The authoritative state is in MySQL.

This is important because:

```text
Application Instance A
Application Instance B
Application Instance C
```

must all observe the same reservation state.

Database transactions and row locks provide that shared coordination mechanism.

---

# 15. Error Semantics

Expected contention is represented as a conflict rather than a server failure.

Typical outcomes:

| Situation | Status |
|---|---:|
| Successful reservation | 201 |
| Seat already taken | 409 |
| Per-user limit exceeded | 409 |
| Invalid idempotency reuse | 409 |
| Unauthorized request | 401 |
| Forbidden operation | 403 |
| Invalid request | 400 |

The reservation flow is designed so expected concurrent contention does not become a `500 Internal Server Error`.

---

# 16. Failure and Partition Considerations

The system uses MySQL as the transactional source of truth.

If the application cannot reach MySQL, reservation correctness cannot safely be decided.

The service therefore does not attempt to maintain an independent inventory state when the database is unavailable.

Readiness includes database health so that an instance that cannot access its database can be considered not ready to receive traffic.

A production implementation would additionally consider:

- Database failover
- Connection pool exhaustion
- Transaction timeouts
- Deadlock retry policies
- Circuit breakers
- Backpressure
- Rate limiting
- Database replication
- Automated recovery

The current implementation keeps the failure model intentionally simple for the take-home scope.

---

# 17. Observability

The application exposes:

```text
/actuator/health
/actuator/health/readiness
/actuator/prometheus
```

Reservation metrics include:

```text
reservations_confirmed_total

reservations_declined_total{reason="seat_taken"}

reservations_declined_total{reason="per_user_limit"}

reservations_declined_total{reason="idempotent_replay"}

reservations_seats_available
```

This allows operators to distinguish between:

- Successful reservations
- Seat contention
- Per-user limit rejection
- Idempotent replay behavior
- Current inventory availability

---

# 18. Swagger / OpenAPI

The service includes interactive API documentation using Springdoc OpenAPI.

Swagger UI:

```text
https://seat-reservation-system-production-89d1.up.railway.app/swagger-ui/index.html
```

OpenAPI specification:

```text
https://seat-reservation-system-production-89d1.up.railway.app/v3/api-docs
```

Swagger provides a convenient evaluation interface for authentication, show creation, inventory, reservation and cancellation APIs.

---

# 19. Correlation IDs

Every HTTP request receives an `X-Request-ID`.

If the client supplies the header, the same ID is propagated.

Otherwise, the application generates one.

The ID is stored in MDC and included in the application log pattern.

This allows a request to be followed across:

```text
HTTP request
     ↓
Controller
     ↓
Service
     ↓
Database operation
     ↓
Application logs
```

---

# 20. Concurrency Testing

The repository contains dedicated PowerShell scripts for concurrency testing.

## Hot-seat test

`burst.ps1` sends concurrent requests for the same seat.

The important invariant is:

```text
Exactly one request → 201
All other conflicting requests → 409
No request → 500
```

A successful run produced:

```text
201 Created : 1
409 Conflict: 19
401 0
403 0
Other 4xx 0
5xx 0
Connection 0

HOT-SEAT TEST PASS
```

The final database/inventory state was also reconciled after the burst.

---

# 21. Per-user Concurrency Test

`user-limit.ps1` tests multiple concurrent reservations from the same user.

With a limit of four seats, the test verifies that:

```text
confirmed seats <= 4
```

A successful run produced:

```text
201 Created : 4
409 Conflict: 6
401 0
403 0
Other 4xx 0
5xx 0
Connection 0

PER-USER LIMIT PASS
```

---

# 22. Automated Tests

The project contains both unit tests and database-backed integration tests.

## Unit Tests

JUnit 5 tests cover:

- Show creation
- Blank seat validation
- Duplicate seat validation
- Show inventory
- Successful reservation
- Already-booked seat rejection
- Per-user reservation limit
- Idempotent replay
- Idempotency-key conflict
- Cancellation
- Non-owner cancellation
- Inventory reconciliation

The unit-test suite contains **12 passing tests**.

Run:

```bash
./gradlew test
```

## Integration Tests

Integration tests use Spring Boot with **Testcontainers and MySQL 8.4**.

They validate the actual database-backed concurrency behavior, including:

- Concurrent hot-seat reservation
- Concurrent same-user reservations
- Per-user limit enforcement
- Same idempotency key replay
- Same idempotency key with a different request
- Cancellation and seat release

The integration suite contains **5 concurrency-focused integration test cases**.

The complete suite is executed with:

```powershell
.\gradlew clean test
```

The full test suite passes.

The concurrency scripts complement the automated tests by exercising actual concurrent HTTP requests against the running service.

---

# 23. Why Not Redis Distributed Locks?

Redis could be used for distributed locking, but it was intentionally not introduced for this implementation.

Doing so would introduce another coordination system:

```text
Application
    │
    ├── Redis lock
    │
    └── MySQL state
```

The correctness of the reservation would then depend on the interaction between the lock system and database state.

For this assignment, the inventory is already stored in MySQL, so MySQL row locking provides a simpler correctness model:

```text
Application
    │
    ▼
MySQL transaction + row locks
```

Redis could still be valuable later for caching, rate limiting or other non-authoritative workloads.

---

# 24. Why Not Kafka?

Kafka is useful for asynchronous workflows and event distribution.

The reservation decision in this assignment is synchronous:

```text
Request
   ↓
Reserve
   ↓
201 / 409
```

Introducing Kafka would add unnecessary asynchronous states and complexity.

Kafka could be introduced later for secondary events such as:

```text
ReservationConfirmed
ReservationCancelled
```

for analytics, notifications or downstream processing.

It is not required for the atomic reservation decision itself.

---

# 25. Why Not `synchronized`?

A Java `synchronized` block protects only threads within the same JVM.

It does not provide distributed correctness when the application is horizontally scaled.

For example:

```text
Instance A
   └── synchronized lock

Instance B
   └── separate synchronized lock
```

Both instances could still attempt to reserve the same database seat.

Database row locking provides a shared concurrency boundary across instances.

---

# 26. Trade-offs

## Chosen approach

```text
Spring Boot
+
MySQL
+
Database Transactions
+
Pessimistic Row Locks
```

### Advantages

- Strong correctness
- Simple mental model
- Durable state
- Easy reconciliation
- Works across application instances
- No additional distributed locking system

### Disadvantages

- Database becomes the primary contention point
- Pessimistic locks can reduce throughput under extreme hot-seat contention
- Requires careful lock ordering
- Database connection/transaction capacity becomes important at high scale

For this assignment, correctness was prioritized over premature infrastructure complexity.

---

# 27. Scaling Considerations

If traffic grows significantly, the first areas to investigate would be:

1. Database connection pool sizing
2. Transaction duration
3. Lock contention
4. Hot-seat distribution
5. Read/write workload separation
6. Database indexing
7. Read replicas for GET-heavy workloads
8. Caching of read-only show information
9. Rate limiting
10. Horizontal application scaling

The reservation write path should continue to keep MySQL as the authoritative source of inventory state.

---

# 28. Production Improvements

A production-grade version could add:

- External identity provider
- Proper token issuance service
- Payment authorization
- Time-boxed holds
- Redis caching
- Distributed tracing
- Centralized logging
- Alerting
- Rate limiting
- Connection pool tuning
- Database replication/failover
- Deadlock retry with bounded backoff
- Kubernetes deployment
- Automated backups
- Disaster recovery
- More extensive load testing

These were intentionally kept outside the scope of the take-home implementation.

---

# 29. Deployment

The application is deployed on Railway.

The deployed architecture contains:

```text
Railway
│
├── Seat Reservation Application
│
└── MySQL
    └── Persistent Volume
```

Application configuration is supplied through environment variables.

Database credentials and JWT secrets are not stored in source control.

---

# 30. AI Usage

AI tools were used during development for:

- Architecture brainstorming
- Concurrency design discussion
- Code assistance
- Debugging
- Test-script development
- Documentation drafting
- Deployment troubleshooting
- Reviewing JUnit and integration test coverage
- Reviewing Swagger/OpenAPI integration

The final implementation and design decisions were reviewed and tested as part of the development process.

The use of AI was not intended to replace understanding of the implementation; the concurrency model, database locking strategy, idempotency behavior and testing results were validated against the running application.

---

# 31. What I Would Do Next

If this service were being taken from a take-home assignment to production, the next priorities would be:

### 1. Proper authentication

Replace the demo token-generation endpoint with an external identity provider.

### 2. Payment integration

Introduce payment authorization while keeping seat ownership transactional.

### 3. Temporary holds

If required by the product flow, introduce time-boxed holds with explicit expiry semantics.

### 4. Load testing

Run sustained load tests at significantly higher concurrency and measure:

- p50 latency
- p95 latency
- p99 latency
- lock wait time
- database CPU
- connection pool utilization
- error rate

### 5. Distributed observability

Add:

- OpenTelemetry
- Distributed tracing
- Centralized logs
- Alerts
- Dashboards

### 6. Database resilience

Introduce:

- Backups
- Failover
- Replication
- Recovery testing

---

# 32. Final Correctness Model

The central correctness model can be summarized as:

```text
                AUTHENTICATED USER
                        │
                        ▼
                 IDEMPOTENCY CHECK
                        │
                        ▼
              LOCK SHOW-USER ROW
                        │
                        ▼
              LOCK SEATS IN ORDER
                        │
                        ▼
              CHECK AVAILABILITY
                        │
                        ▼
             CHECK USER BOOKING LIMIT
                        │
                        ▼
               CREATE RESERVATION
                        │
                        ▼
              CONFIRM ALL SEATS
                        │
                        ▼
                   COMMIT
```

Therefore:

```text
No double booking
        +
No user-limit bypass
        +
No duplicate retry reservation
        +
Atomic multi-seat behavior
        +
Consistent inventory
```

are all enforced around the same transactional source of truth.

---

# Conclusion

The implementation intentionally favors a simple and strongly consistent design:

```text
MySQL
  +
Transactions
  +
Pessimistic Row Locks
  +
Deterministic Lock Ordering
  +
Idempotency
  +
Atomic Multi-seat Reservation
```

This provides a clear correctness model for the assignment while keeping the service small enough to deploy, test and operate as a single application.

The deployed service and repository include the API implementation, database migrations, concurrency test scripts, observability endpoints, Docker setup and engineering documentation.
