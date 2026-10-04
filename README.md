# Seat Reservation at Scale

A concurrency-safe seat reservation service built with **Java 21, Spring Boot, MySQL and JPA**, designed to handle high-contention reservation requests while guaranteeing that a seat cannot be sold twice.

## Live Deployment

**Live API:**

https://seat-reservation-system-production-89d1.up.railway.app

The application is deployed on Railway with:

- Spring Boot application
- MySQL 8.4
- Persistent MySQL volume
- Environment-based configuration

---

## Features

- Show creation with assigned seats
- Concurrent seat reservations
- No double booking
- Per-user booking limits
- Idempotent reservation requests
- Atomic multi-seat reservations
- Explicit reservation cancellation
- JWT authentication
- Health and readiness probes
- Prometheus metrics
- Correlation IDs
- Docker support
- Concurrent burst testing
- JUnit 5 unit tests
- MySQL Testcontainers integration tests
- Swagger / OpenAPI documentation
- Liquibase database migrations

---

## Tech Stack

| Technology | Purpose |
|---|---|
| Java 21 | Application runtime |
| Spring Boot | REST API |
| Spring Data JPA | Persistence |
| Hibernate | ORM |
| MySQL 8.4 | Primary database |
| Liquibase | Database migrations |
| Spring Security | Authentication |
| JWT | Authentication tokens |
| Micrometer | Application metrics |
| Prometheus | Metrics endpoint |
| Springdoc OpenAPI | Swagger / OpenAPI documentation |
| JUnit 5 | Unit testing |
| Testcontainers | MySQL integration testing |
| Gradle | Build |
| Docker | Containerization |
| Railway | Deployment |

---

# Architecture

```text
                    ┌─────────────────────┐
                    │       Client        │
                    │                     │
                    │ cURL / Postman /    │
                    │ Burst Scripts       │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │   Spring Boot API   │
                    │                     │
                    │ Controllers         │
                    │ Security / JWT      │
                    │ Validation          │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │  ReservationService │
                    │                     │
                    │ Transactions        │
                    │ Idempotency         │
                    │ Per-user limits     │
                    │ Seat locking        │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │       MySQL         │
                    │                     │
                    │ shows               │
                    │ seats               │
                    │ reservations        │
                    │ reservation_seats   │
                    │ show_users          │
                    │ idempotency_keys    │
                    └─────────────────────┘
```

MySQL is the source of truth for reservation correctness.

---

# Reservation Semantics

## Reservation Lifecycle

Reservations are confirmed immediately.

There is no temporary hold state.

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

## Multi-seat Reservation

Multi-seat reservations are **all-or-nothing**.

For example:

```json
{
  "seats": ["A1", "A2", "A3"]
}
```

If any requested seat is unavailable, the complete request is rejected.

No partial reservation is created.

## Per-user Limit

The default booking limit is:

```text
4 seats per user per show
```

The limit is enforced atomically under concurrent requests.

## Idempotency

Every reservation requires an `idempotency_key`.

```text
Same key + same request
        │
        ▼
Return original reservation

Same key + different request
        │
        ▼
409 Conflict
```

## Cancellation

Cancellation is explicit.

Only the reservation owner can cancel the reservation.

Cancellation releases all seats atomically.

---

# API Endpoints

| Method | Endpoint | Authentication |
|---|---|---|
| POST | `/auth/token` | Public |
| POST | `/shows` | ADMIN |
| GET | `/shows/{id}` | Public |
| POST | `/shows/{id}/reserve` | USER |
| POST | `/reservations/{id}/cancel` | USER |
| GET | `/actuator/health` | Public |
| GET | `/actuator/health/readiness` | Public |
| GET | `/actuator/prometheus` | Public |
| GET | `/swagger-ui/index.html` | Public |
| GET | `/v3/api-docs` | Public |

---

# Authentication

For evaluation convenience, the deployed environment exposes a token-generation endpoint.

> This endpoint is intended for the take-home evaluation/demo environment. A production system would use a proper identity provider rather than exposing arbitrary token generation.

## Generate ADMIN Token

### Request

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/auth/token?userId=admin1&role=ADMIN"
```

The response is the JWT token.

Save it as:

```bash
export ADMIN_TOKEN="<ADMIN_TOKEN>"
```

PowerShell:

```powershell
$ADMIN_TOKEN="<ADMIN_TOKEN>"
```

---

## Generate USER Token

### Request

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/auth/token?userId=user1&role=USER"
```

Save it as:

```bash
export USER_TOKEN="<USER_TOKEN>"
```

PowerShell:

```powershell
$USER_TOKEN="<USER_TOKEN>"
```

---

# API Examples

The following examples use the live Railway deployment.

---

# 1. Create Show

Only an `ADMIN` can create a show.

### Request

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/shows" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Paytm Demo Concert",
    "seats": ["A1", "A2", "A3", "A4", "A5"],
    "price_paise": 10000
  }'
```

### Example Response

```json
{
  "id": 1,
  "name": "Paytm Demo Concert",
  "totalSeats": 5,
  "availableSeats": 5
}
```

The returned `id` is the `showId`.

---

# 2. Get Show Details

### Request

```bash
curl "https://seat-reservation-system-production-89d1.up.railway.app/shows/1"
```

### Example Response

```json
{
  "id": 1,
  "name": "Paytm Demo Concert",
  "totalSeats": 5,
  "availableSeats": 5,
  "heldSeats": 0,
  "confirmedSeats": 0,
  "seats": [
    {
      "seatNumber": "A1",
      "status": "AVAILABLE"
    },
    {
      "seatNumber": "A2",
      "status": "AVAILABLE"
    },
    {
      "seatNumber": "A3",
      "status": "AVAILABLE"
    },
    {
      "seatNumber": "A4",
      "status": "AVAILABLE"
    },
    {
      "seatNumber": "A5",
      "status": "AVAILABLE"
    }
  ]
}
```

The following invariant is maintained:

```text
totalSeats = availableSeats + heldSeats + confirmedSeats
```

This implementation does not use temporary holds, therefore:

```text
heldSeats = 0
```

---

# 3. Reserve a Seat

### Request

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/shows/1/reserve" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "seats": ["A1"],
    "idempotency_key": "reserve-a1-001"
  }'
```

### Expected Status

```text
201 Created
```

### Example Response

```json
{
  "reservationId": 1,
  "showId": 1,
  "userId": "user1",
  "seats": [
    "A1"
  ],
  "amountPaise": 10000,
  "status": "CONFIRMED"
}
```

The seat is now confirmed.

---

# 4. Idempotent Retry

Retry the exact same request.

### Request

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/shows/1/reserve" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "seats": ["A1"],
    "idempotency_key": "reserve-a1-001"
  }'
```

The same logical reservation is returned.

A second reservation is not created.

This protects against:

- Network retries
- Client retries
- Request timeouts
- Duplicate submissions

---

# 5. Same Idempotency Key With Different Request

The original request used:

```text
idempotency_key = reserve-a1-001
seat = A1
```

Trying to reuse the same key with another seat:

### Request

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/shows/1/reserve" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "seats": ["A2"],
    "idempotency_key": "reserve-a1-001"
  }'
```

### Expected

```text
409 Conflict
```

This prevents an idempotency key from being reused for a different operation.

---

# 6. Reserve Multiple Seats

### Request

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/shows/1/reserve" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "seats": ["A3", "A4"],
    "idempotency_key": "reserve-a3-a4-001"
  }'
```

### Example Response

```json
{
  "reservationId": 2,
  "showId": 1,
  "userId": "user1",
  "seats": [
    "A3",
    "A4"
  ],
  "amountPaise": 20000,
  "status": "CONFIRMED"
}
```

---

# 7. Concurrent Hot Seat Reservation

If multiple users attempt to reserve the same seat concurrently:

```text
A2
```

exactly one request can succeed.

Expected result:

```text
1 × 201 Created
N × 409 Conflict
0 × 500
```

The winning transaction changes:

```text
A2: AVAILABLE → CONFIRMED
```

Other concurrent requests receive:

```text
409 Conflict
```

---

# 8. Per-user Booking Limit

Default limit:

```text
4 seats per user per show
```

The `show_users` table maintains the current seat count for each user/show combination.

Example:

```text
Request 1 → 2 seats → SUCCESS
Request 2 → 2 seats → SUCCESS
Request 3 → 1 seat  → 409 Conflict
```

The limit is enforced while holding the corresponding database row lock.

---

# 9. Cancel Reservation

Only the reservation owner can cancel.

### Request

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/reservations/1/cancel" \
  -H "Authorization: Bearer $USER_TOKEN"
```

The cancellation:

1. Locks the reservation.
2. Verifies ownership.
3. Locks the user's show row.
4. Locks the reservation seats.
5. Marks the reservation as cancelled.
6. Releases the seats.
7. Updates the per-user seat count.

The operation is transactional.

---

# 10. Get Show After Cancellation

### Request

```bash
curl "https://seat-reservation-system-production-89d1.up.railway.app/shows/1"
```

The cancelled seats should become:

```text
AVAILABLE
```

The inventory counts should reconcile again.

---

# Swagger / OpenAPI

The service includes interactive API documentation using Springdoc OpenAPI.

Swagger UI:

```text
https://seat-reservation-system-production-89d1.up.railway.app/swagger-ui/index.html
```

OpenAPI specification:

```text
https://seat-reservation-system-production-89d1.up.railway.app/v3/api-docs
```

Swagger can be used to evaluate authentication, show creation, show inventory, reservations and cancellation without manually constructing every request.

---

# Health and Observability

## Health

```bash
curl "https://seat-reservation-system-production-89d1.up.railway.app/actuator/health"
```

Used for general application health.

---

## Readiness

```bash
curl "https://seat-reservation-system-production-89d1.up.railway.app/actuator/health/readiness"
```

Readiness includes database availability.

---

## Prometheus Metrics

```bash
curl "https://seat-reservation-system-production-89d1.up.railway.app/actuator/prometheus"
```

Important application metrics include:

```text
reservations_confirmed_total

reservations_declined_total{reason="seat_taken"}

reservations_declined_total{reason="per_user_limit"}

reservations_declined_total{reason="idempotent_replay"}

reservations_seats_available
```

---

# Correlation IDs

Every request receives an `X-Request-ID`.

If the client provides:

```text
X-Request-ID: abc-123
```

the same ID is returned in the response.

If no ID is provided, the application generates a UUID.

The ID is also included in application logs through MDC.

Example:

```text
2026-10-03 12:30:10.123 INFO [abc-123] ReservationService - Reservation created
```

This allows a request to be correlated with application logs.

---

# Concurrency Correctness

The reservation operation executes inside a database transaction.

The system uses pessimistic row-level locking.

Conceptually:

```text
BEGIN TRANSACTION

        │
        ▼
Validate idempotency
        │
        ▼
Lock user/show state
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
COMMIT
```

The database transaction is the atomic decision point.

---

# Seat Locking

Requested seats are locked using:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
```

Only one concurrent transaction can successfully modify a particular seat at a time.

This prevents double booking.

---

# Deterministic Lock Ordering

Multi-seat requests can cause deadlocks if different transactions acquire locks in different orders.

Example of a problematic pattern:

```text
Transaction A:
A1 → A2

Transaction B:
A2 → A1
```

To avoid this, requested seats are sorted before locking.

Therefore both transactions use:

```text
A1 → A2
```

This provides deterministic lock ordering.

---

# Per-user Concurrency

The `show_users` table stores:

```text
(show_id, user_id, seat_count)
```

The row is locked before checking/updating the count.

Therefore concurrent requests from the same user cannot independently pass the booking-limit check.

---

# Idempotency Implementation

The `idempotency_keys` table stores:

```text
show_id
user_id
idempotency_key
request_hash
reservation_id
created_at
```

A unique constraint exists on:

```text
(show_id, user_id, idempotency_key)
```

The request hash prevents reuse of the same key for a different request payload.

---

# Database Model

```text
shows
  │
  ├────────────── seats
  │
  ├────────────── reservations
  │                    │
  │                    └──── reservation_seats
  │
  └────────────── show_users

idempotency_keys
       │
       └──────────── reservations
```

## `shows`

```text
id
name
price_paise
per_user_limit
created_at
```

## `seats`

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

```text
reservation_id
show_id
seat_number
```

## `show_users`

```text
show_id
user_id
seat_count
```

Primary key:

```text
(show_id, user_id)
```

## `idempotency_keys`

```text
id
show_id
user_id
idempotency_key
request_hash
reservation_id
created_at
```

Unique constraint:

```text
(show_id, user_id, idempotency_key)
```

---

# Money Representation

Prices are stored as integer paise.

Example:

```text
₹100 = 10000 paise
```

This avoids floating-point precision issues.

---

# Testing

The project contains both unit tests and database-backed integration tests.

## Unit Tests

JUnit 5 tests cover the core business behavior, including:

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

Current unit-test coverage contains **12 passing tests**.

Run the automated test suite:


```bash
./gradlew test
```

Windows PowerShell:

```powershell
.\gradlew test
```

---

# Integration Tests

The project also contains Spring Boot integration tests using **Testcontainers with MySQL 8.4**.

The integration suite validates behavior against a real MySQL database and covers:

- Concurrent hot-seat reservation
- Concurrent same-user reservations
- Per-user limit enforcement
- Same idempotency key replay
- Same idempotency key with a different request
- Cancellation and seat release

The integration suite contains **5 concurrency-focused integration test cases**.

Run the complete test suite:

```powershell
.\gradlew clean test
```

The full test suite passes.

---

# Concurrency Burst Test

The repository contains:

```text
burst.ps1
```

The script sends concurrent requests against a hot seat.

Expected:

```text
Exactly one successful reservation
Remaining requests → 409 Conflict
No 5xx responses
```

Example successful result:

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

The script also verifies the final seat state and reconciliation.

Run:

```powershell
.\burst.ps1
```

---

# Per-user Limit Test

The repository contains:

```text
user-limit.ps1
```

Run:

```powershell
.\user-limit.ps1
```

The test verifies that concurrent requests cannot exceed the configured per-user limit.

Example successful result:

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

# Correctness Test

The repository also contains:

```text
correctness.ps1
```

Run:

```powershell
.\correctness.ps1
```

The test validates:

- No double booking
- No 5xx responses
- Correct available-seat count
- Correct confirmed-seat count
- Per-user limit
- Reservation reconciliation

---

# Running Locally

## Prerequisites

- Java 21
- Docker
- Docker Compose
- Git

---

## Start MySQL

```bash
docker compose up -d mysql
```

Verify:

```bash
docker compose ps
```

MySQL is exposed locally on:

```text
localhost:3307
```

---

## Run Application

```bash
./gradlew bootRun
```

Windows:

```powershell
.\gradlew bootRun
```

Application:

```text
http://localhost:8080
```

---

# Local Authentication

The token endpoint is disabled by default.

For local evaluation:

```powershell
$env:TOKEN_ENDPOINT_ENABLED="true"
.\gradlew bootRun
```

Then:

```bash
curl -X POST "http://localhost:8080/auth/token?userId=admin1&role=ADMIN"
```

and:

```bash
curl -X POST "http://localhost:8080/auth/token?userId=user1&role=USER"
```

---

# Docker

Build and start the complete stack:

```bash
docker compose up --build
```

Application:

```text
http://localhost:8080
```

MySQL:

```text
localhost:3307
```

Stop:

```bash
docker compose down
```

---

# Environment Variables

The application supports environment-based configuration.

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
JWT_SECRET
TOKEN_ENDPOINT_ENABLED
```

Example:

```text
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3307/seat_reservation
SPRING_DATASOURCE_USERNAME=reservation
SPRING_DATASOURCE_PASSWORD=reservation
JWT_SECRET=<strong-secret>
TOKEN_ENDPOINT_ENABLED=false
```

Secrets should never be committed to Git.

---

# Liquibase

Database schema changes are managed using Liquibase.

Master changelog:

```text
src/main/resources/db/changelog/db.changelog-master.xml
```

Changesets include:

```text
001-create-shows-and-seats.xml
002-create-reservation-tables.xml
```

Hibernate uses:

```text
ddl-auto: validate
```

Therefore Hibernate validates the schema instead of creating/modifying tables.

---

# Error Handling

| Situation | Response |
|---|---|
| Successful reservation | `201 Created` |
| Seat already reserved | `409 Conflict` |
| Per-user limit exceeded | `409 Conflict` |
| Invalid idempotency reuse | `409 Conflict` |
| Unauthorized request | `401 Unauthorized` |
| Forbidden operation | `403 Forbidden` |
| Invalid request | `400 Bad Request` |

Expected reservation contention is represented using `409 Conflict`, rather than `500 Internal Server Error`.

---

# Why MySQL Row Locks?

MySQL is the source of truth for inventory correctness.

Using database transactions and row-level locks provides:

- Strong correctness
- Atomic updates
- Durable state
- Simple deployment
- No distributed locking infrastructure
- Easy reconciliation

Redis/distributed locking was intentionally avoided because it would introduce another consistency boundary that is unnecessary for this implementation.

---

# Why No Kafka / Message Queue?

Reservation correctness requires an immediate decision.

A queue-based workflow would introduce additional states such as:

```text
REQUESTED
   ↓
PENDING
   ↓
CONFIRMED / FAILED
```

The assignment requires an immediate `201` or `409`.

Therefore reservation processing is synchronous and transactional.

---

# Why No Temporary Holds?

This implementation uses explicit cancellation rather than time-boxed holds.

The lifecycle is:

```text
AVAILABLE
    ↓
CONFIRMED
    ↓
AVAILABLE
```

This avoids:

- Background schedulers
- Hold expiration races
- Additional state transitions
- Cleanup jobs

A production ticketing system could introduce time-boxed holds if payment processing requires them.

---

# Production Improvements

Possible future improvements include:

- External identity provider
- Payment service integration
- Time-boxed seat holds
- Redis for caching/read-heavy workloads
- Database read replicas
- Connection pool tuning
- Distributed tracing
- Centralized logging
- Rate limiting
- Circuit breakers
- Horizontal scaling
- Kubernetes deployment
- Deadlock retry strategy
- Automated backups
- Alerting
- More extensive load testing

These are intentionally outside the scope of this take-home implementation.

---

# Project Structure

```text
.
├── src
│   ├── main
│   │   ├── java
│   │   │   └── com.paytm.reservation
│   │   │       ├── config
│   │   │       ├── controller
│   │   │       ├── dto
│   │   │       ├── exception
│   │   │       ├── filter
│   │   │       ├── model
│   │   │       ├── repository
│   │   │       └── service
│   │   │
│   │   └── resources
│   │       ├── db/changelog
│   │       └── application.yml
│   │
│   └── test
│
├── burst.ps1
├── user-limit.ps1
├── correctness.ps1
├── Dockerfile
├── docker-compose.yml
├── build.gradle
├── settings.gradle
├── gradlew
├── gradlew.bat
├── README.md
└── WRITEUP.md
```

---

# Quick Evaluation Flow

The complete live evaluation can be performed using the following sequence.

## 1. Generate Admin Token

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/auth/token?userId=admin1&role=ADMIN"
```

## 2. Generate User Token

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/auth/token?userId=user1&role=USER"
```

## 3. Create Show

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/shows" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Evaluation Show",
    "seats": ["A1", "A2", "A3", "A4", "A5"],
    "price_paise": 10000
  }'
```

## 4. Check Inventory

```bash
curl "https://seat-reservation-system-production-89d1.up.railway.app/shows/1"
```

## 5. Reserve Seat

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/shows/1/reserve" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "seats": ["A1"],
    "idempotency_key": "evaluation-001"
  }'
```

## 6. Retry Same Request

Send the exact same request again.

The same reservation should be returned.

## 7. Verify Inventory

```bash
curl "https://seat-reservation-system-production-89d1.up.railway.app/shows/1"
```

Verify:

```text
totalSeats = availableSeats + heldSeats + confirmedSeats
```

## 8. Cancel Reservation

```bash
curl -X POST "https://seat-reservation-system-production-89d1.up.railway.app/reservations/1/cancel" \
  -H "Authorization: Bearer $USER_TOKEN"
```

## 9. Verify Inventory Again

```bash
curl "https://seat-reservation-system-production-89d1.up.railway.app/shows/1"
```

The cancelled seat should be available again.

## 10. Check Health

```bash
curl "https://seat-reservation-system-production-89d1.up.railway.app/actuator/health"
```

## 11. Check Readiness

```bash
curl "https://seat-reservation-system-production-89d1.up.railway.app/actuator/health/readiness"
```

## 12. Check Prometheus

```bash
curl "https://seat-reservation-system-production-89d1.up.railway.app/actuator/prometheus"
```

---

# AI Usage

AI tools were used during development for:

- Design brainstorming
- Reviewing concurrency approaches
- Code assistance
- Test-script development
- Documentation drafting
- Debugging build/deployment issues
- Reviewing JUnit and integration test coverage
- Reviewing Swagger/OpenAPI integration

The final implementation, architecture, concurrency mechanism, API behavior, database schema and deployment were reviewed and tested as part of the project.

---

# Design Summary

The central design principle is:

> **Make the database transaction the atomic decision point for reservation correctness.**

Reservation flow:

```text
Request
   │
   ▼
Authenticate
   │
   ▼
Validate idempotency
   │
   ▼
Lock user/show state
   │
   ▼
Lock requested seats
   │
   ▼
Validate availability
   │
   ▼
Validate user limit
   │
   ▼
Create reservation
   │
   ▼
Mark seats CONFIRMED
   │
   ▼
Commit transaction
   │
   ▼
201 Created
```

Under contention:

```text
Multiple concurrent requests
            │
            ▼
      Database locks
            │
            ▼
     One transaction wins
            │
       ┌────┴────┐
       ▼         ▼
      201       409
    Success   Conflict
```

This provides a simple, durable and explainable correctness model for high-contention seat reservation.

---

# License

This project was created as a take-home engineering assignment.
