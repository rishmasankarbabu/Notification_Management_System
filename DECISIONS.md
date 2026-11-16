# Design decisions

An overview of the decisions made. Each section states the choice, the
alternatives I considered, why I chose this one, and what it costs.

## 1. Persistence: PostgreSQL with Spring Data JPA

- **Considered:** in-memory H2 with Spring Data JPA. H2 would be quick to start, but its in-memory database loses rows when it shuts down and does not exercise the same database engine as the application.
- **Why PostgreSQL + JPA:** filtering and newest-first pagination run in the database; transactional updates and the entity's `@Version` field protect concurrent changes. Keeping PostgreSQL means notification rows survive backend restarts and are still there for a later run, rather than resetting the demo's data. Docker Compose provides a reproducible local PostgreSQL setup without requiring a host installation, and its named volume retains rows across database-container restarts.
- **Trade-off:** reviewers need Docker and must start PostgreSQL before the backend; persistent local data can also carry over between runs. The volume can be deliberately cleared with `docker compose down -v`. This adds setup compared with in-memory H2, but provides durable data and exercises the database the application is designed to use. The application is delivered in a deployable form and can be used as the production application, with PostgreSQL configured for the deployment environment.

## 2. Where the business rules live

The lifecycle (`OPEN -> IN_PROGRESS -> RESOLVED`, note required to resolve) is enforced inside the `Notification`
entity and `NotificationStatus` enum, not in controllers or the service. REST calls and JMS messages both go through the same code, and no caller can bypass the rules. The backend also returns `nextStatus` with every notification, so the UI never duplicates the transition table.

## 3. Status change API: `PATCH /{id}/status` with the target status

- **Considered:** `POST /{id}/advance` ("move to whatever is next").
- **Why explicit target:** with "advance", two operators clicking at the same time on an `OPEN` notification would
  move it twice (`OPEN -> IN_PROGRESS -> RESOLVED`), and the second one never chose that. With an explicit target the
  second request fails with `409`, which is the correct outcome.
- **Concurrency:** the entity has a `@Version` field. If two requests race past each other, the loser gets `409` with a
  readable message instead of silently overwriting. The UI reloads on `409`.
- **Error codes:** `409` = valid request, wrong state; `400` = the request itself is wrong (missing note).
  The note is only used when resolving.

## 4. Consuming JMS and the dead letter queue (Artemis broker)

- **Broker-managed retries and DLQ.** The listener container uses a transacted JMS session. If the listener throws, the message is rolled back for redelivery; Artemis routes it to `DLQ` after its configured delivery-attempt limit. The integration test configures three attempts and verifies this behavior. The retry limit and DLQ address are broker configuration, not application-level guarantees.
- **Invalid messages and processing failures.** Malformed JSON, unsupported message types, missing JMS message IDs, and validation failures all throw from the listener, so they are retried and eventually dead-lettered under the broker policy. Database or other processing failures also roll back and are retried; they may succeed on redelivery.
- **Validation and test coverage.** `AlertMessageListener` parses the text and validates `IncomingAlertDto` before calling `NotificationService`. The listener has unit tests for valid, invalid, and malformed messages, and an embedded-Artemis integration test verifies eventual DLQ routing.
- **Redelivery deduplication.** The listener requires the provider-assigned `JMSMessageID` and passes it through to persistence. Existing IDs are checked before creating a notification, and `source_message_id` has a unique constraint as a final safeguard. This handles redelivery with the same JMS ID; it does not deduplicate separately sent messages with different IDs.
- **Payload compatibility.** `description` has no DTO length constraint. Unknown JSON fields are rejected by the configured Jackson deserialization setting, so producers must keep the payload aligned with `IncomingAlertDto`.


## 5. Real-time updates: Server-Sent Events

- **Considered:** WebSocket, polling.
- **Why SSE:** traffic is one-way (server to browser); user actions are normal REST calls. SSE is plain HTTP, the browser reconnects automatically, and it needs no extra library or protocol. WebSocket would add a handshake, message framing and reconnect logic for no benefit here. Polling would mean delay plus constant empty requests.
- **Events are sent after the database commit** (`@TransactionalEventListener`), so a browser never sees data that was rolled back.
- **Concurrency:** after a change is saved, the backend sends it to each browser connected to this server. HTTP actions and JMS messages can both trigger sends. The connected-browser list can safely change while a send is in progress, but the hub does not queue notifications to run one after another. This simple in-process approach is intended for a small operator group; use with many clients would need load testing.
- **Scaling limit:** each backend instance tracks only the browsers connected to it. With multiple instances, an update handled by one instance would not reach browsers connected to another. A shared channel (such as an Artemis topic or Redis pub/sub) would let instances forward updates to their own connected browsers.

## 6. Handling volume: server-side paging

- Newest first, 25 per page (max 100), optional `status` filter, optional `severity` filter The database does the work, and the browser never holds the full list.
- **Offset paging over keyset paging:** operators navigate between pages and see a total count, which offset paging supports directly.
  Keyset would be better for a very large, rapidly changing table.
- **Live updates** new notifications are inserted at the top of page 1. 

## 7. Frontend structure

- **Local state:** React hooks manage the form, credentials, current page, filters, loading state, errors and live-connection status. The app has no additional state-management or component library.
- **Live updates:** SSE events trigger a reload of the current page and filters; the frontend does not merge event data into the list. There is no separate `notificationState.ts` module or frontend unit test for merging.
- **Errors:** login errors appear on the login form;  Resolution notes are requested with the browser's `window.prompt`, not a dialog. API errors use the backend's `detail` or `title` when available; network errors use the browser's error message.

## 8. CORS

An explicit allow-list (`app.cors.allowed-origins`) applied to `/api/**` only, never `*`. The allow-list is environment-specific and should be updated in the configuration files (e.g., `application.yml` or environment variables) when adding new frontend origins. In development, Vite proxies `/api`
(including the SSE stream) to the backend during development, so the browser sees one origin; in production, the frontend and backend may be served from different origins, so correct CORS configuration remains necessary.

## 9. Operations and documentation

Actuator exposes `health` (includes the JMS connection), `info` and `metrics`. A counter
`notifications.incoming{outcome=accepted|rejected}` shows alert flow at a glance, which is the first thing an operator would want when alerts "stop arriving". Swagger UI is generated from the code, so it cannot drift from the API.

## 10. Testing strategy

Tests check that notifications can be created, listed and moved through allowed statuses, and that invalid API requests and malformed or invalid JMS alerts are rejected. An embedded Artemis test verifies that an invalid alert is retried and then sent to the DLQ; a separate test checks that reusing a JMS message ID does not create a duplicate notification. Other tests cover the SSE stream, rate limiting, and the health and API documentation endpoints. The tests do not verify a valid JMS message all the way through to database storage.


