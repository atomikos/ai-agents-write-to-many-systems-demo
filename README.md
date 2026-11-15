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

Needs Java 17+ and Docker (the Maven wrapper downloads Maven).

```bash
docker compose up -d          # PostgreSQL (prepared transactions enabled) and Artemis
./mvnw spring-boot:run
```

Refund an order (orders `order-1001` to `order-1006` are seeded):

```bash
curl -s -H 'Content-Type: application/json' \
     -d '{"refundId":"r-1","orderId":"order-1001"}' localhost:8080/refunds

curl -s localhost:8080/state   # what every system knows: orders, sagas, billing, emails, CRM
```

Or open **http://localhost:8080/**: a button to refund an order, a button for
every row of the article's failure table, one button per other fault, and a
table that shows what each system knows about every order and whether they
agree, refreshed twice a second. Switch **Mode** to *Without coordination* to
run the same buttons against the refund as four separate calls. When the app is
down the page says so and picks up again after a restart, so you can watch the
sweep clean up. **Reset** puts all orders back to `PAID`.

`refundId` is the caller's idempotency key: repeating the call never refunds twice,
it reports the outcome of the first call.

| Outcome | HTTP | Meaning |
|---|---|---|
| `REFUNDED` | 200 | all four systems updated (the email follows from the queue) |
| `FAILED` | 409 | nothing done; the CRM update, if any, is reverted |
| `IN_PROGRESS` | 202 | an earlier call with this key has not finished (or crashed and is not yet cleaned up) |

## The failure table, with and without coordination

The article's table lists what goes wrong when an agent refunds by calling four
tools in a row. `POST /naive-refunds` does exactly that - four steps, each
committing on its own, a new refund on every call. Don't do this; it is here so
you can watch it go wrong next to the hybrid pattern.

| Row of the table | Fault | Without coordination | With the hybrid pattern |
|---|---|---|---|
| Crash after step 1 | `CRASH_AFTER_DB_UPDATE` | order `REFUNDED`, no event: billing never refunds the money | after restart: order `PAID`, CRM note `REVERTED` |
| Broker timeout on step 2, agent retries | `BROKER_ANSWER_LOST`, then call again | two events: billing books the refund twice | first call `FAILED` with nothing left behind; the retry refunds once |
| CRM call fails at step 3 | `CRM_REJECTS` | order and billing say refunded, the CRM has no note | `FAILED`, nothing changed anywhere |
| Crash after step 4, agent replays the plan | `CRASH_AFTER_COMMIT`, then call again | two confirmation emails | the replay with the same `refundId` returns `REFUNDED`; one email |

```bash
curl -s -H 'Content-Type: application/json' \
     -d '{"orderId":"order-1003","fault":"CRM_REJECTS"}' localhost:8080/naive-refunds
```

## Provoke the failures

Click the fault's button on the page, or add `"fault"` to the request and look at `/state`.

| Fault | What happens | What you should see |
|---|---|---|
| `CRM_REJECTS` | CRM refuses the update | `FAILED`, no CRM note, order still `PAID` |
| `CRM_FAILS_AFTER_APPLY` | CRM applies the update but returns an error | `FAILED`, CRM note found by its key and `REVERTED` |
| `FAIL_AFTER_DB_UPDATE` | exception after the order row is updated | `FAILED`, order `PAID`, no event, no email, CRM note `REVERTED` |
| `CRASH_AFTER_DB_UPDATE` | JVM killed after the order row is updated | after restart: order `PAID`, no event, no email; the sweep reverts the CRM note |
| `BROKER_ANSWER_LOST` | the broker takes the event, but the answer is lost | `FAILED`, order `PAID`, no event, no email, CRM note `REVERTED` |
| `FAIL_BEFORE_COMMIT` | exception after all three writes | same |
| `CRASH_BEFORE_COMMIT` | JVM killed after all three writes | after restart: order `PAID`, no event, no email; the sweep reverts the CRM note |
| `CRASH_AFTER_PREPARE` | JVM killed in the middle of the commit: both prepared, PostgreSQL not committed | an in-doubt transaction in PostgreSQL; after restart, recovery commits it: order `REFUNDED`, one ledger row, one email |
| `CRASH_AFTER_COMMIT` | JVM killed before the caller gets an answer | after restart: retry with the same `refundId` returns `REFUNDED`; one ledger row, one email |
| `CRASH_AFTER_CRM` | JVM killed between the CRM update and the transaction | after restart: the sweep reverts the CRM note |

After a `CRASH_*` fault, start the application again. A refund left `PENDING` by a crash
is compensated once it is older than `refund.saga.stale-after` (30 seconds).

```bash
curl -s -H 'Content-Type: application/json' \
     -d '{"refundId":"r-2","orderId":"order-1002","fault":"CRASH_BEFORE_COMMIT"}' localhost:8080/refunds
./mvnw spring-boot:run
```

## Tests

```bash
./mvnw test
```

The tests start PostgreSQL and Artemis with Testcontainers. Those with a crash run
the application in a JVM of its own, halt it at the fault, and start it again on
the same transaction log - so recovery and the sweep run as after a real crash.
The test names are the claims they check. Every test refunds a new paid order of
a new customer. "Refunded once" means: order `REFUNDED`, one billing booking, one
email, one active CRM note - and still so three seconds later, so a late duplicate
would show. "Nothing changed" means: order `PAID`, no booking, no email, no active
CRM note - also three seconds later. A crash is `Runtime.halt`, with no cleanup,
and the test checks that the JVM halted at that point and nowhere else.

**The failure table, without coordination** (`POST /naive-refunds`; these tests pass because things go wrong)

| Test | What it does | What it checks |
|---|---|---|
| Crash after step 1 → order refunded, but no event: billing never refunds the money | crash right after the order update, before the event; restart | order stays `REFUNDED`, billing has no booking - also three seconds later |
| Broker timeout on step 2, agent retries → event published twice: the customer is refunded twice | the event is sent but the caller gets an error; the agent calls again | billing has two bookings |
| CRM call fails at step 3 → database and broker say "refunded", CRM says "open" | the CRM rejects the update | order `REFUNDED`, one booking, no active CRM note |
| Crash after step 4, agent replays the plan → two confirmation emails | crash after all four steps, before the answer; restart; the agent calls again | two emails |

**The failure table, with the hybrid pattern** (the same four failures, `POST /refunds`)

| Test | What it does | What it checks |
|---|---|---|
| Crash after step 1 → database and broker roll back together, and the CRM update is undone | crash right after the order update, inside the XA transaction; restart | nothing changed: recovery rolls back, the sweep reverts the CRM note |
| Broker timeout on step 2, agent retries → the retry starts clean: refunded once | the first call fails after the event is sent, inside XA; the agent retries with a new `refundId` | first `FAILED`, retry `REFUNDED`, then refunded once |
| CRM call fails at step 3 → nothing changed anywhere | the CRM rejects; it goes first, so nothing else happened yet | `FAILED`, nothing changed |
| Crash after step 4, agent replays the plan → one confirmation email | crash after the commit, before the answer; restart; the agent calls again with the same `refundId` | the replay returns `REFUNDED`, then refunded once - one email |

**Crash recovery** (real crashes of the hybrid refund)

| Test | What it does | What it checks |
|---|---|---|
| A crash between the CRM update and the transaction → the minimal coordinator undoes the CRM update | crash after the CRM update, before the XA transaction; restart | nothing changed; asking again with the same `refundId` returns `FAILED` |
| A crash after all three writes, before the commit → they roll back together, and the CRM update is undone | crash after order, event and email request are written, before the commit; restart | nothing changed |
| "A JVM crash in the middle is resolved by recovery on restart" → the commit is completed | crash in the middle of the two-phase commit: PostgreSQL and Artemis prepared, the decision logged, PostgreSQL not committed; restart | refunded once, asking again returns `REFUNDED`, no transaction left in doubt in PostgreSQL |

**The hybrid pattern** (no crashes; the application runs inside the test)

| Test | What it checks |
|---|---|
| A refund reaches all four systems, once | a plain refund arrives in every system exactly once |
| A transactional core: a failure inside it rolls back database and broker together, and the CRM is compensated | after an exception after the order update, or just before the commit: database and broker roll back together, the CRM note is reverted |
| Compensation for the rest: a CRM update with unknown outcome is found by its key and reverted | the CRM applies the update but returns an error; the update is found by its key and reverted |
| Compensation for the rest: a rejected CRM update leaves nothing to revert | the CRM rejects; nothing is left behind |
| One tool: calling again with the same refundId never refunds twice | the second call reports the outcome of the first |
| An order is not refunded twice under a different refundId | a second refund of the same order is refused by the order's status check |
| The minimal coordinator: the sweep compensates a refund abandoned by a crash | a saga left `PENDING` is picked up by the sweep and compensated |

## Where to look

- `RefundService` - the business action: saga row, CRM call, then the XA core.
- `RefundCompensator` - undoes the CRM step from durable state; a scheduled sweep
  picks up refunds abandoned by a crash. A minimal saga coordinator that runs
  inside the application, with no extra server.
- `RefundSagaRepository` - saga status changes are guarded updates. Completing the
  saga is part of the XA transaction, so "refund committed" and "nothing left to
  compensate" are the same fact.
- `crm/` - the CRM client and the fake CRM.
- `downstream/` - billing and the email sender; each consumes its message and
  writes its row in one XA transaction.
- `NaiveRefundService` - the same refund without coordination, for comparison.
- `CrashAfterPrepareConfiguration` - demo plumbing that halts the JVM in the middle
  of the two-phase commit.

## Where this demo stops

`RefundCompensator` shows the idea in a few dozen lines: a saga record in the same
database, completed inside the XA transaction, and a sweep that finishes abandoned
compensations after a restart. A production coordinator needs more - retries with
backoff, monitoring, more than one compensated step, and recovery that is tested
against every crash point. The tools named in the article provide that: Temporal
and MicroProfile LRA (Narayana) with a separate coordinator, Eventuate Tram Sagas
over messaging, and DBOS as a library inside the application.

## Limits of the demo

- The fake CRM keeps its notes in the same PostgreSQL database, only so that they
  survive the crashes. It is called over HTTP and commits independently.
- The "email" is a log line and a `sent_email` row. A real SMTP send cannot join
  the transaction; pass the `refundId` to the mail provider as an idempotency key.
- Fault injection is always on and can halt the JVM: do not expose this application.
- The Atomikos transaction log is written to `transaction-logs/`. Deleting it while
  transactions are in doubt breaks recovery.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
