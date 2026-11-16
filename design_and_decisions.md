# Design and Decisions

This document describes the system, its implementation, and the reasons
behind the main design choices. For local setup, see
[INSTRUCTIONS.md](./INSTRUCTIONS.md). A component diagram is available in
[architecture-diagram.md](./architecture-diagram.md).

## 1. High-level design

The application has three parts: the alert simulator, a Spring Boot backend,
and a React frontend. There are two separate inbound flows into the backend:
the simulator submits alerts through JMS, and operators submit actions through
REST. These flows converge at the notification service. A third, outbound
flow sends committed changes to browsers through SSE.

```text
INBOUND FLOW A — alerts
Alert simulator -> Artemis queue -> JMS listener -> validate message --+
                                                                       |
INBOUND FLOW B — operator actions                                      +-> Notification service -> PostgreSQL
Browser -> REST controller -> validate request -----------------------+              |
                                                                        after commit |
OUTBOUND FLOW — live updates                                                      SSE hub -> connected browsers
```

The JMS listener and REST controller remain separate entry points, each
validating its own input before calling the shared notification service. SSE
is not another input path: it broadcasts changes only after the database
transaction commits. A detailed diagram is in
[architecture-diagram.md](./architecture-diagram.md).

## 2. Backend structure and startup

`NotificationBoardApplication` is the Spring Boot entry point. It starts the
application, enables JMS, and lets Spring scan the `com.cup.notificationboard`
packages for controllers, services, repositories, listeners, and configuration.

The backend follows familiar Spring layers:

- **API/controllers:** map HTTP routes to service calls and DTOs.
- **Messaging/listener:** receives and validates Artemis messages.
- **Service:** owns notification creation, status transitions, persistence,
  and change events.
- **DTOs:** define and validate the incoming alert, API requests, and
  responses; the entity/model represents persisted notification data.
- **Repository:** performs database reads, filtering, paging, and writes.
- **Configuration/security:** sets up database/broker connections, CORS,
  authentication, CSRF, rate limits, and API documentation.

This separation keeps transport details out of the business rules. REST and
JMS are different entry points, but neither duplicates the notification
lifecycle logic.

## 3. Alert ingestion and validation

The simulator publishes a JSON `TextMessage` to `alert.notifications` every
15-45 seconds. `AlertMessageListener` then:

1. Rejects non-text messages and messages without a JMS message ID.
2. Deserializes JSON into `IncomingAlertDto`.
3. Validates the payload before calling `NotificationService`.

| Input | Rule |
|---|---|
| `title` | Required, non-blank, at most 100 characters |
| `description` | Optional |
| `severity` | Required: `LOW`, `MEDIUM`, `HIGH`, or `CRITICAL` |

Malformed JSON, a JSON `null`, unknown JSON properties, unknown severity
values, or validation failures are rejected. The listener throws instead of
storing invalid data. Its JMS session is transacted, so Artemis can redeliver
failed messages. The retry limit and DLQ address are broker settings; the
embedded-Artemis integration test configures three deliveries and verifies
that a repeatedly rejected message reaches `DLQ`.

The JMS message ID is stored with a unique constraint to suppress duplicate
rows when a message with the same ID is redelivered. This does not deduplicate
separate messages that have different IDs. Alerts are created directly by the
listener calling the service; this path does not make an HTTP request to the
REST API.

## 4. Notification model and API

The `Severity` and `NotificationStatus` enums constrain values in the
application. A notification starts as `OPEN` and can move only through
`OPEN -> IN_PROGRESS -> RESOLVED`. Resolving requires a non-blank resolution
note. The entity records creation/update times and uses a version field to
detect concurrent updates.

| API | Purpose |
|---|---|
| `GET /api/notifications` | List newest first; page size defaults to 25 and is capped at 100; supports status/severity filters |
| `GET /api/notifications/{id}` | Get one notification |
| `POST /api/notifications` | Create an `OPEN` notification for API clients |
| `PATCH /api/notifications/{id}/status` | Request a specific target status |
| `GET /api/notifications/events` | Subscribe to notification SSE events |
| `GET /api/csrf` | Get a CSRF token for write requests |

`ApiExceptionHandler` turns invalid input into `400`, missing records into
`404`, and illegal transitions or concurrent updates into `409` responses.
Malformed request bodies and parameters are also reported as `400`.

## 5. Persistence, configuration, and security

PostgreSQL was chosen for structured data, filtering, and pagination.
Compared with in-memory H2, it keeps rows across backend restarts and exercises
the same database engine used by the application. The local Compose file runs
PostgreSQL in a container with a named volume. Rows survive database-container
restarts; `docker compose down -v` deliberately removes the local data. The
trade-off is that reviewers need Docker and data can carry over between runs.
Production deployments still need their own managed database and secure
configuration.

Flyway owns schema changes; Hibernate validates the schema rather than
recreating it. The schema has a primary key, a unique JMS message ID, and an
index on `created_at`.

`application.yml` reads the database password, broker settings, and operator
credentials from environment variables. The local Compose file currently
sets its PostgreSQL password directly. Only PostgreSQL is containerized; the
Spring Boot application itself is started with Maven, and there is no backend
Dockerfile yet.

For this code-test setup, security is deliberately small: one in-memory
operator, HTTP Basic authentication, encoded password, CSRF protection for
write requests, explicit CORS configuration, and a 120-request-per-minute
per-IP API limit. The health endpoint is public; other endpoints require
authentication. This is not a multi-user identity system.

CORS uses the `app.cors.allowed-origin` setting and currently allows
`http://localhost:5173`; its backend mapping is `/**`. In development, Vite
proxies `/api` requests through the frontend origin, so the browser sees one
origin. In production, if frontend and backend use different origins, the
backend must allow the deployed frontend origin.

## 6. Status changes

Notifications move through `OPEN -> IN_PROGRESS -> RESOLVED`. Resolving
requires a non-blank note. The status endpoint takes an explicit target
rather than blindly advancing the notification. This prevents a repeated or
concurrent request from silently moving it farther than the operator
intended. Optimistic-lock conflicts and invalid transitions return `409`;
invalid requests, such as a missing resolution note, return `400`.

## 7. Pagination

The list endpoint returns notifications newest first, 25 per page by default
(maximum 100), with optional `status` and `severity` filters. The database
performs the filtering and paging, so the browser does not need to hold the
full list.

The backend uses offset paging. It supports page-number navigation and a total
count directly, which matches the UI's Previous/Next controls and page count.
Keyset paging could be more efficient for very large tables or deep traversal,
but is less suited to numbered pages and would need a separate count query if
the UI still needs totals.

## 8. Live updates and frontend behavior

SSE was selected because updates flow only from the backend to browsers.
Polling would repeatedly request unchanged data and delay updates between
polls. WebSockets support two-way communication, which this use case does not
need and would add connection/protocol complexity.

After a database transaction commits, the SSE publisher sends the change to
the in-process hub. Every connected client on that backend process receives
the event. The hub's connections are held in memory, so a change handled by
one backend instance will not reach browsers connected to another; multiple
instances would need shared event distribution, such as a broker topic or
Redis pub/sub.

The server does not retain events for disconnected clients or send heartbeat
comments. The frontend reconnects after stream failures with backoff and
reloads its current page after reconnecting. Missed events are not replayed.
The existing SSE test verifies event delivery to a client; there is no
browser end-to-end test with two clients.

The frontend uses React and TypeScript with Vite. It has no create form.
Notifications may arrive from the simulator through JMS or be created through
the backend REST API by another API client. The frontend displays
server-paginated notifications, supports status/severity filters, and offers
inline actions: `OPEN` notifications can be started, and `IN_PROGRESS`
notifications can be resolved after the operator provides a resolution note.
API requests and SSE handling are centralized in a typed API module.

The frontend uses React hooks without an additional state-management or
component library. It reloads the current page after SSE events instead of
merging event data into the list. Status changes are not optimistic: the UI
waits for the server response and then reloads the page. The action button is
not disabled while a status request is in flight. Each tab has its own
in-memory sign-in state and must sign in separately.

## 9. Tests and validation

Backend tests cover:

- REST creation, listing/page metadata, invalid input, status transitions,
  OpenAPI, health, and duplicate JMS message IDs.
- JMS payload parsing/validation and dead-letter routing after repeated
  failures.
- SSE delivery and API rate limiting.

The duplicate-ID test calls the service directly. The embedded-Artemis
integration test verifies DLQ routing for an invalid message; tests do not
verify a valid JMS message all the way through to database storage. The
database-backed tests use H2 and the DLQ integration test uses an embedded
Artemis broker; they do not require the local PostgreSQL container or simulator.

Run backend tests with `mvn test` from `backend`. The frontend has no
automated test suite yet; `npm run build` from `frontend` runs the TypeScript
check and production build.

## 10. Trade-offs and next steps

- **Multiple operators:** replace the in-memory user with OIDC sign-in,
  individual accounts, roles, and authorization checks. Use a secure shared
  session strategy rather than storing passwords in browser storage.
- **More backend instances:** the current SSE hub and rate limiter are local
  to one process. Add shared event distribution, preferably through a
  transactional outbox and broker, and move rate limiting to a shared store
  or gateway.
- **Growing data:** keep managed PostgreSQL backups and a retention policy.
  Review query plans before adding indexes; status/severity plus creation-time
  indexes may help common filters. Archive or partition only when volume
  justifies the operational cost.
- **Container deployment:** add a Dockerfile for the Spring Boot backend and
  run it as a container on any supported container platform. Keep secrets
  outside the image and configure health checks. The current Compose setup
  runs PostgreSQL only.
- **Operations:** use TLS and managed secrets, and add logs, metrics, tracing,
  and load tests.
- **End-to-end testing:** use Playwright to test the resolve-with-note flow.
