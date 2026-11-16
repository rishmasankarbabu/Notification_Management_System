# Design Notes

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
failed messages; the simulator routes them to `DLQ` after three attempts.
The JMS message ID is stored with a unique constraint to suppress duplicate
rows when a valid message is redelivered. This is how simulator alerts are
created: the listener calls the service directly, without making an HTTP
request to the REST API.

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
| `PATCH /api/notifications/{id}/status` | Advance to the next valid status |
| `GET /api/notifications/events` | Subscribe to notification SSE events |
| `GET /api/csrf` | Get a CSRF token for write requests |

`ApiExceptionHandler` turns invalid input into `400`, missing records into
`404`, and illegal transitions or concurrent updates into `409` responses.
Malformed request bodies and parameters are also reported as `400`.

## 5. Persistence, configuration, and security

PostgreSQL was chosen for structured data, filtering, and pagination. Flyway
owns schema changes; Hibernate validates the schema rather than recreating it.
The schema has a primary key, a unique JMS message ID, and an index on
`created_at`.

The local Compose file runs PostgreSQL in a container with a named volume.
Rows therefore survive backend and database-container restarts. The root
README documents `docker compose down -v` to deliberately delete the local
database.

`application.yml` externalizes the database password, broker URL, operator
credentials, and allowed CORS origin. Only PostgreSQL is currently
containerized; the Spring Boot application itself is started with Maven, and
there is no backend Dockerfile yet.

For this code-test setup, security is deliberately small: one in-memory
operator, HTTP Basic authentication, encoded password, CSRF protection for
write requests, explicit CORS configuration, and a 120-request-per-minute
per-IP API limit. The health endpoint is public; other endpoints require
authentication. CORS allows the configured frontend origin, and Vite proxies
local `/api` requests through the frontend origin during development. This is
not a multi-user identity system.

## 6. Live updates and frontend behavior

SSE was selected because updates flow only from the backend to browsers.
Polling would repeatedly request unchanged data and delay updates between
polls. WebSockets support two-way communication, which this use case does not
need and would add connection/protocol complexity.

After a database transaction commits, the SSE publisher sends the change to
the in-process hub. Every connected client on that backend process receives
the event. The React frontend reloads its current page from the API, keeping
the database/API authoritative. Thus, two authenticated browser clients
connected to the same backend see status changes without manually refreshing.
The frontend reconnects after stream failures and reloads the page on
reconnection. Each client must have signed in and have an open SSE connection;
the existing SSE test verifies event delivery to a client, but there is no
browser end-to-end test with two clients.

The frontend uses React and TypeScript with Vite. It has no create form.
Notifications may arrive from the simulator through JMS or be created through
the backend REST API by another API client. The frontend displays
server-paginated notifications, supports status/severity filters, and offers
inline actions: `OPEN` notifications can be started, and `IN_PROGRESS`
notifications can be resolved after the operator provides a resolution note.
API requests and SSE handling are centralized in a typed API module.

The hub is process-local and does not retain events. Disconnected clients can
miss an event, so the frontend reloads on reconnect. Each tab also has its own
in-memory sign-in state and must sign in separately.

## 7. Tests and validation

Backend tests cover:

- REST creation, paging, filters, invalid input, status transitions, OpenAPI,
  health, and duplicate JMS message IDs.
- JMS payload parsing/validation and dead-letter routing after repeated
  failures.
- SSE delivery and API rate limiting.

Run them with `mvn test` from `backend`. The frontend has no automated test
suite yet; `npm run build` from `frontend` runs the TypeScript check and
production build.

The backend tests use H2 for database-backed tests and an embedded Artemis
broker for the dead-letter integration test; they do not require the local
PostgreSQL container or simulator.

## 8. Tradeoffs and next steps

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
- **End-to-End test:** use Playwright to perform end to end test of the the "resolve with note" flow.

