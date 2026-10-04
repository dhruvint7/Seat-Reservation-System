
---

### 2. Create `WRITEUP.md`

```markdown
# Design Write-up

## 1. Atomic Reservation Mechanism

The reservation operation is executed inside a database transaction.

The application first locks the requesting user's quota row and then locks the requested seat rows. After validation, the reservation, reservation-seat mappings, seat status changes and user quota update are committed as one transaction.

If any validation fails, the transaction is rolled back.

MySQL is therefore the source of truth for the reservation decision.

## 2. Preventing Double Booking

Requested seat rows are acquired using pessimistic write locking.

Two concurrent requests attempting to reserve the same seat cannot both acquire the seat lock and successfully confirm it.

The first transaction confirms the seat and commits. The next transaction observes the updated seat state and receives a conflict response.

This prevents the same seat from being sold twice.

## 3. Per-User Concurrency Limit

A `show_users` row maintains the number of confirmed seats held by a user for a show.

The row is locked using a pessimistic write lock before checking the user's quota.

The default limit is four seats.

Because concurrent requests for the same user serialize on this row, concurrent requests cannot collectively exceed the configured limit.

## 4. Multi-Seat Deadlock Avoidance

Multi-seat requests lock multiple seat rows.

Before acquiring locks, the requested seat numbers are sorted.

All reservation operations therefore acquire seat locks in the same deterministic order.

Cancellation follows the same user-quota-before-seat locking order.

This reduces the possibility of circular wait between concurrent multi-seat operations.

## 5. Idempotency

Each reservation request contains an idempotency key.

The key is stored together with:

- show ID
- user ID
- request hash
- resulting reservation ID

The request hash allows the service to distinguish between:

- retrying the same request
- reusing the same idempotency key for a different request

The first case returns the original reservation while the second case is rejected with `409 Conflict`.

## 6. Cancellation

Cancellation is explicit rather than time-boxed.

Only the reservation owner can cancel the reservation.

Cancellation executes transactionally and:

1. Locks the reservation.
2. Locks the user's quota row.
3. Locks the reservation's seats.
4. Marks the reservation cancelled.
5. Releases the seats.
6. Decrements the user's confirmed seat count.

The transaction either completes fully or rolls back.

## 7. Partial Reservation Semantics

The service uses **all-or-nothing** semantics for multi-seat requests.

For example, if a request contains:

```text
A1, A2, A3