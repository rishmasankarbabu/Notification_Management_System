import { useCallback, useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import {
  advanceNotification,
  fetchNotifications,
  streamNotifications,
} from "./api";
import type {
  Credentials,
  Notification,
  NotificationPage,
  NotificationStatus,
} from "./api";

const PAGE_SIZE = 25;

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Something went wrong. Please try again.";
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

function nextStatus(status: NotificationStatus): NotificationStatus | null {
  if (status === "OPEN") return "IN_PROGRESS";
  if (status === "IN_PROGRESS") return "RESOLVED";
  return null;
}

function App() {
  const [username, setUsername] = useState("operator");
  const [password, setPassword] = useState("");
  const [credentials, setCredentials] = useState<Credentials | null>(null);
  const [page, setPage] = useState<NotificationPage | null>(null);
  const [pageNumber, setPageNumber] = useState(0);
  const [statusFilter, setStatusFilter] = useState("");
  const [severityFilter, setSeverityFilter] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [liveState, setLiveState] = useState<"connecting" | "live" | "reconnecting">("connecting");
  const currentQuery = useRef({ pageNumber, statusFilter, severityFilter });

  useEffect(() => {
    currentQuery.current = { pageNumber, statusFilter, severityFilter };
  }, [pageNumber, statusFilter, severityFilter]);

  const loadPage = useCallback(async (
    auth: Credentials,
    targetPage: number,
    status: string,
    severityValue: string,
    signal?: AbortSignal,
  ) => {
    setLoading(true);
    setError("");
    try {
      const result = await fetchNotifications(auth, targetPage, status, severityValue, signal);
      if (!signal?.aborted) {
        setPage(result);
        setPageNumber(result.page);
      }
    } catch (loadError) {
      if (!signal?.aborted) setError(errorMessage(loadError));
      throw loadError;
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!credentials) return;
    const controller = new AbortController();
    void loadPage(credentials, pageNumber, statusFilter, severityFilter, controller.signal).catch(() => undefined);
    return () => controller.abort();
  }, [credentials, pageNumber, statusFilter, severityFilter, loadPage]);

  useEffect(() => {
    if (!credentials) return;
    const controller = new AbortController();
    let retryCount = 0;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;

    const reconnect = async () => {
      while (!controller.signal.aborted) {
        try {
          setLiveState(retryCount === 0 ? "connecting" : "reconnecting");
          await streamNotifications(
            credentials,
            controller.signal,
            () => {
              setLiveState("live");
              if (retryCount > 0) {
                const query = currentQuery.current;
                void loadPage(credentials, query.pageNumber, query.statusFilter, query.severityFilter, controller.signal)
                  .catch(() => undefined);
              }
            },
            (eventType) => {
              const query = currentQuery.current;
              const targetPage = eventType === "notification-created" ? 0 : query.pageNumber;
              if (eventType === "notification-created") setPageNumber(0);
              void loadPage(credentials, targetPage, query.statusFilter, query.severityFilter, controller.signal)
                .catch(() => undefined);
            },
          );
        } catch {
          if (controller.signal.aborted) return;
        }

        if (controller.signal.aborted) return;
        retryCount += 1;
        setLiveState("reconnecting");
        await new Promise<void>((resolve) => {
          retryTimer = setTimeout(resolve, Math.min(1000 * 2 ** Math.min(retryCount, 4), 15000));
        });
      }
    };

    void reconnect();
    return () => {
      controller.abort();
      if (retryTimer) clearTimeout(retryTimer);
    };
  }, [credentials, loadPage]);

  async function handleLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const auth = { username: username.trim(), password };
    if (!auth.username || !auth.password) {
      setError("Enter both your username and password.");
      return;
    }
    try {
      const firstPage = await fetchNotifications(auth, 0, "", "");
      setError("");
      setPage(firstPage);
      setPageNumber(0);
      setCredentials(auth);
    } catch (loginError) {
      setError(errorMessage(loginError));
    }
  }

  async function handleAdvance(notification: Notification) {
    const targetStatus = nextStatus(notification.status);
    if (!credentials || !targetStatus) return;

    let resolutionNote: string | undefined;
    if (targetStatus === "RESOLVED") {
      const note = window.prompt("Add a resolution note:");
      if (note === null) return;
      if (!note.trim()) {
        setError("A resolution note is required.");
        return;
      }
      resolutionNote = note.trim();
    }

    try {
      await advanceNotification(credentials, notification.id, targetStatus, resolutionNote);
      await loadPage(credentials, pageNumber, statusFilter, severityFilter);
    } catch (updateError) {
      setError(errorMessage(updateError));
    }
  }

  if (!credentials) {
    return (
      <main className="login-page">
        <form className="login-card" onSubmit={handleLogin}>
          <div className="brand-mark" aria-hidden="true">N</div>
          <p className="eyebrow">OPERATIONS CONSOLE</p>
          <h1>Notification Board</h1>
          <p className="muted">Sign in with your operator account to continue.</p>
          {error && <div className="alert error-alert" role="alert">{error}</div>}
          <label>
            Username
            <input autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} />
          </label>
          <label>
            Password
            <input
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <button className="primary-button full-width" type="submit">Sign in</button>
          <p className="login-footnote">Credentials are kept in memory only for this session.</p>
        </form>
      </main>
    );
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <a className="brand" href="/" aria-label="Notification Board home">
          <span className="brand-mark small" aria-hidden="true">N</span>
          <span>Notification Board</span>
        </a>
        <div className="account">
          <span className="account-avatar" aria-hidden="true">{credentials.username.slice(0, 1).toUpperCase()}</span>
          <span className="account-name">{credentials.username}</span>
          <button className="text-button" onClick={() => { setCredentials(null); setPage(null); setPassword(""); }}>
            Sign out
          </button>
        </div>
      </header>

      <section className="page-heading">
        <div>
          <p className="eyebrow">INCIDENT MANAGEMENT</p>
          <h1>Notifications</h1>
          <p className="muted">Review incoming alerts and track their resolution.</p>
        </div>
        <div className={`live-indicator ${liveState}`} role="status">
          <span className="live-dot" />
          {liveState === "live" ? "Live updates on" : liveState === "connecting" ? "Connecting to live updates" : "Reconnecting"}
        </div>
      </section>

      {error && (
        <div className="alert error-alert page-alert" role="alert">
          <span>{error}</span>
          <button className="dismiss-button" aria-label="Dismiss error" onClick={() => setError("")}>×</button>
        </div>
      )}

      <section className="summary-grid" aria-label="Notification summary">
        <div className="summary-card">
          <span className="summary-icon total-icon" aria-hidden="true">▤</span>
          <div><span className="summary-label">Total notifications</span><strong>{page?.totalElements ?? "—"}</strong></div>
        </div>
        <div className="summary-card">
          <span className="summary-icon open-icon" aria-hidden="true">◷</span>
          <div><span className="summary-label">On this page</span><strong>{page?.content.length ?? "—"}</strong></div>
        </div>
        <div className="summary-card">
          <span className="summary-icon live-icon" aria-hidden="true">↗</span>
          <div><span className="summary-label">Connection</span><strong className="connection-value">{liveState === "live" ? "Connected" : "Waiting"}</strong></div>
        </div>
      </section>

      <section className="list-panel">
        <div className="list-heading">
          <div>
            <h2>All notifications</h2>
            <p className="muted">{page?.totalElements ?? 0} items · newest first</p>
          </div>
          <div className="filters">
            <label>
              <span className="sr-only">Filter by status</span>
              <select value={statusFilter} onChange={(event) => { setStatusFilter(event.target.value); setPageNumber(0); }}>
                <option value="">All statuses</option>
                <option value="OPEN">Open</option>
                <option value="IN_PROGRESS">In progress</option>
                <option value="RESOLVED">Resolved</option>
              </select>
            </label>
            <label>
              <span className="sr-only">Filter by severity</span>
              <select value={severityFilter} onChange={(event) => { setSeverityFilter(event.target.value); setPageNumber(0); }}>
                <option value="">All severities</option>
                <option value="CRITICAL">Critical</option>
                <option value="HIGH">High</option>
                <option value="MEDIUM">Medium</option>
                <option value="LOW">Low</option>
              </select>
            </label>
            <button
              className="icon-button"
              aria-label="Refresh notifications"
              title="Refresh notifications"
              disabled={loading}
              onClick={() => void loadPage(credentials, pageNumber, statusFilter, severityFilter)}
            >
              ↻
            </button>
          </div>
        </div>

        {loading && !page ? (
          <div className="empty-state"><span className="spinner" /> Loading notifications…</div>
        ) : page && page.content.length > 0 ? (
          <>
            <div className="notification-list">
              {page.content.map((notification) => {
                const next = nextStatus(notification.status);
                return (
                  <article className="notification-row" key={notification.id}>
                    <span className={`severity-indicator ${notification.severity.toLowerCase()}`} aria-hidden="true" />
                    <div className="notification-main">
                      <div className="notification-title-line">
                        <h3>{notification.title}</h3>
                        <span className={`severity-badge ${notification.severity.toLowerCase()}`}>
                          {notification.severity}
                        </span>
                      </div>
                      {notification.description && <p className="notification-description">{notification.description}</p>}
                      {notification.resolutionNote && (
                        <p className="resolution-note"><strong>Resolution:</strong> {notification.resolutionNote}</p>
                      )}
                      <div className="notification-meta">
                        <span>{formatDate(notification.createdAt)}</span>
                        <span className={`status-badge ${notification.status.toLowerCase()}`}>
                          <span className="status-dot" />{notification.status.replace("_", " ")}
                        </span>
                      </div>
                    </div>
                    <div className="notification-action">
                      {next ? (
                        <button
                          className={next === "RESOLVED" ? "secondary-button" : "primary-button"}
                          onClick={() => void handleAdvance(notification)}
                        >
                          {next === "RESOLVED" ? "Resolve" : "Start work"}
                        </button>
                      ) : <span className="completed-label">Complete</span>}
                    </div>
                  </article>
                );
              })}
            </div>
            <div className="pagination">
              <span>Page {page.totalPages === 0 ? 0 : page.page + 1} of {page.totalPages}</span>
              <div>
                <button
                  className="secondary-button"
                  disabled={page.page === 0 || loading}
                  onClick={() => setPageNumber((current) => Math.max(0, current - 1))}
                >
                  Previous
                </button>
                <button
                  className="secondary-button"
                  disabled={page.page + 1 >= page.totalPages || loading}
                  onClick={() => setPageNumber((current) => current + 1)}
                >
                  Next
                </button>
              </div>
            </div>
          </>
        ) : (
          <div className="empty-state">
            {loading ? <><span className="spinner" /> Loading notifications…</> : "No notifications match these filters."}
          </div>
        )}
      </section>
      <footer className="footer-note">Showing {Math.min(PAGE_SIZE, page?.totalElements ?? 0)} notifications per page</footer>
    </main>
  );
}

export default App;
