export type NotificationStatus = "OPEN" | "IN_PROGRESS" | "RESOLVED";
export type Severity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export interface Notification {
  id: string;
  title: string;
  description: string | null;
  severity: Severity;
  status: NotificationStatus;
  createdAt: string;
  updatedAt: string;
  resolutionNote: string | null;
  resolvedAt: string | null;
}

export interface NotificationPage {
  content: Notification[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Credentials {
  username: string;
  password: string;
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/+$/, "");

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

function authorization(credentials: Credentials): string {
  return `Basic ${btoa(`${credentials.username}:${credentials.password}`)}`;
}

async function responseError(response: Response): Promise<ApiError> {
  let message = `Request failed (${response.status})`;
  try {
    const body: unknown = await response.json();
    if (typeof body === "object" && body !== null && "detail" in body && typeof body.detail === "string") {
      message = body.detail;
    } else if (typeof body === "object" && body !== null && "title" in body && typeof body.title === "string") {
      message = body.title;
    }
  } catch {
    if (response.status === 401) {
      message = "Sign-in failed. Check your username and password.";
    }
  }
  if (response.status === 401) {
    message = "Sign-in failed or your session is no longer authorized.";
  }
  return new ApiError(message, response.status);
}

async function request<T>(
  path: string,
  credentials: Credentials,
  options: {
    method?: string;
    body?: unknown;
    csrfToken?: string;
    signal?: AbortSignal;
  } = {},
): Promise<T> {
  const headers = new Headers({
    Authorization: authorization(credentials),
    Accept: "application/json",
  });
  if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }
  if (options.csrfToken) {
    headers.set("X-XSRF-TOKEN", options.csrfToken);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: options.method ?? "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    credentials: "include",
    signal: options.signal,
  });
  if (!response.ok) {
    throw await responseError(response);
  }
  return response.json() as Promise<T>;
}

export async function fetchNotifications(
  credentials: Credentials,
  page: number,
  status: string,
  severity: string,
  signal?: AbortSignal,
): Promise<NotificationPage> {
  const query = new URLSearchParams({ page: String(page), size: "25" });
  if (status) query.set("status", status);
  if (severity) query.set("severity", severity);
  return request<NotificationPage>(`/api/notifications?${query}`, credentials, { signal });
}

export async function fetchCsrfToken(credentials: Credentials): Promise<string> {
  const result = await request<{ token: string }>("/api/csrf", credentials);
  return result.token;
}

export async function advanceNotification(
  credentials: Credentials,
  id: string,
  status: NotificationStatus,
  resolutionNote?: string,
): Promise<Notification> {
  const csrfToken = await fetchCsrfToken(credentials);
  return request<Notification>(`/api/notifications/${id}/status`, credentials, {
    method: "PATCH",
    body: { status, ...(resolutionNote === undefined ? {} : { resolutionNote }) },
    csrfToken,
  });
}

export async function streamNotifications(
  credentials: Credentials,
  signal: AbortSignal,
  onConnected: () => void,
  onNotification: (eventType: string, notification: Notification) => void,
): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/notifications/events`, {
    headers: {
      Authorization: authorization(credentials),
      Accept: "text/event-stream",
    },
    credentials: "include",
    signal,
  });
  if (!response.ok) {
    throw await responseError(response);
  }
  if (!response.body) {
    throw new ApiError("The live notification stream is unavailable.");
  }
  onConnected();

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    buffer = buffer.replace(/\r\n/g, "\n");
    let boundary = buffer.indexOf("\n\n");
    while (boundary >= 0) {
      const eventBlock = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const eventType = eventBlock.match(/^event:\s*(.+)$/m)?.[1]?.trim();
      const data = eventBlock
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart())
        .join("\n");
      if (eventType && data) {
        onNotification(eventType, JSON.parse(data) as Notification);
      }
      boundary = buffer.indexOf("\n\n");
    }
    if (done) return;
  }
}

export { API_BASE_URL };
