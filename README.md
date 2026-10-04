# Seat Reservation at Scale

A concurrent seat reservation service built with Java 21 and Spring Boot, designed to maintain reservation correctness under high concurrency.

## Features

- Create shows with assigned seats
- Concurrent seat reservations
- No double booking
- Per-user reservation limit
- Idempotent reservation requests
- Multi-seat reservations with all-or-nothing semantics
- Explicit reservation cancellation
- JWT authentication
- Correlation/request IDs
- Health and readiness probes
- Prometheus metrics
- Docker support
- Concurrency and correctness test scripts

## Tech Stack

- Java 21
- Spring Boot
- Spring Data JPA / Hibernate
- MySQL 8.4
- Liquibase
- Spring Security + JWT
- Micrometer + Prometheus
- Gradle
- Docker / Docker Compose
- JUnit 5

## Architecture

The application uses MySQL as the source of truth for reservation state.

A reservation request runs inside a database transaction:

1. Validate the show and request.
2. Lock the requesting user's quota row.
3. Validate the per-user limit.
4. Sort requested seats deterministically.
5. Acquire pessimistic write locks on the requested seat rows.
6. Validate that all seats are available.
7. Create the reservation and reservation-seat records.
8. Mark the seats as confirmed.
9. Update the user's seat count.
10. Store the idempotency mapping.
11. Commit the transaction.

This makes the reservation decision atomic.

## Running Locally

### Prerequisites

- Java 21
- Docker
- Docker Compose

### Using Gradle

Start MySQL:

```powershell
docker compose up -d mysql