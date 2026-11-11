# Refund demo: XA where you can, sagas where you must

A Spring Boot 4 application that implements the hybrid pattern from the article
*When AI Agents Write to Many Systems*. An agent refunds a customer by calling
**one tool**, `POST /refunds`, and gets back "it worked" or "it didn't".

| Step | System | How it is made consistent |
|---|---|---|
| Mark the order refunded | PostgreSQL | XA transaction |
| Publish `RefundIssued` | Artemis queue `refund.events` | same XA transaction |
| Send the confirmation email | Artemis queue `email.requests` | request written in the same XA transaction, sent after commit |
| Update the CRM | HTTP API (fake, in this app) | idempotency key + compensation, driven by a durable saga row |

The transaction manager is Atomikos (`transactions-spring-boot4-starter` 6.0.1).

## Run it

Needs Java 17+, Maven and Docker.

```bash
docker compose up -d          # PostgreSQL (prepared transactions enabled) and Artemis
mvn spring-boot:run
```

Refund an order (orders `order-1001` to `order-1006` are seeded):

```bash
curl -s -H 'Content-Type: application/json' \
     -d '{"refundId":"r-1","orderId":"order-1001"}' localhost:8080/refunds

curl -s localhost:8080/state   # what every system knows: orders, sagas, billing, emails, CRM
```

`refundId` is the caller's idempotency key: repeating the call never refunds twice,
it reports the outcome of the first call.

| Outcome | HTTP | Meaning |
|---|---|---|
| `REFUNDED` | 200 | all four systems updated (the email follows from the queue) |
| `FAILED` | 409 | nothing done; the CRM update, if any, is reverted |
| `IN_PROGRESS` | 202 | an earlier call with this key has not finished (or crashed and is not yet cleaned up) |

## Provoke the failures

Add `"fault"` to the request, then look at `/state`.

| Fault | What happens | What you should see |
|---|---|---|
| `CRM_REJECTS` | CRM refuses the update | `FAILED`, no CRM note, order still `PAID` |
| `CRM_FAILS_AFTER_APPLY` | CRM applies the update but returns an error | `FAILED`, CRM note found by its key and `REVERTED` |
| `FAIL_AFTER_DB_UPDATE` | exception after the order row is updated | `FAILED`, order `PAID`, no event, no email, CRM note `REVERTED` |
| `FAIL_BEFORE_COMMIT` | exception after all three writes | same |
| `CRASH_BEFORE_COMMIT` | JVM killed after all three writes | after restart: order `PAID`, no event, no email; the sweep reverts the CRM note |
| `CRASH_AFTER_COMMIT` | JVM killed before the caller gets an answer | after restart: retry with the same `refundId` returns `REFUNDED`; one ledger row, one email |
| `CRASH_AFTER_CRM` | JVM killed between the CRM update and the transaction | after restart: the sweep reverts the CRM note |

After a `CRASH_*` fault, start the application again. A refund left `PENDING` by a crash
is compensated once it is older than `refund.saga.stale-after` (30 seconds).

```bash
curl -s -H 'Content-Type: application/json' \
     -d '{"refundId":"r-2","orderId":"order-1002","fault":"CRASH_BEFORE_COMMIT"}' localhost:8080/refunds
mvn spring-boot:run
```

## Tests

```bash
mvn test
```

The tests start PostgreSQL and Artemis with Testcontainers and cover every fault
that does not kill the JVM. The `CRASH_*` faults are manual, as above.

## Where to look

- `RefundService` - the business action: saga row, CRM call, then the XA core.
- `RefundCompensator` - undoes the CRM step from durable state; a scheduled sweep
  picks up refunds abandoned by a crash. A minimal stand-in for a saga coordinator
  such as Temporal, DBOS or LRA.
- `RefundSagaRepository` - saga status changes are guarded updates. Completing the
  saga is part of the XA transaction, so "refund committed" and "nothing left to
  compensate" are the same fact.
- `crm/` - the CRM client and the fake CRM.
- `downstream/` - billing and the email sender; each consumes its message and
  writes its row in one XA transaction.

## Limits of the demo

- The fake CRM keeps its notes in the same PostgreSQL database, only so that they
  survive the crashes. It is called over HTTP and commits independently.
- The "email" is a log line and a `sent_email` row. A real SMTP send cannot join
  the transaction; pass the `refundId` to the mail provider as an idempotency key.
- There is no fault for a crash between prepare and commit. Atomikos recovery
  handles that case, but the demo cannot trigger it from a request.
- Fault injection is always on and can halt the JVM: do not expose this application.
- The Atomikos transaction log is written to `transaction-logs/`. Deleting it while
  transactions are in doubt breaks recovery.
