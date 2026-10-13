export class HttpError extends Error {
  constructor(public readonly status: number, message: string) { super(message); }
}
export class NetworkError extends Error {}
export class RequestTimeoutError extends Error {
  constructor() { super("The server took too long to respond. Try again or choose a shorter date range."); }
}

/** Compatible with the supported browsers; listeners and timers have an explicit lifetime. */
export function requestScope(signal?: AbortSignal, timeoutMs = 45_000) {
  const controller = new AbortController();
  const abort = () => controller.abort(signal?.reason ?? new DOMException("Request cancelled", "AbortError"));
  if (signal?.aborted) abort();
  else signal?.addEventListener("abort", abort, { once: true });
  const timer = setTimeout(() => controller.abort(new RequestTimeoutError()), timeoutMs);
  return {
    signal: controller.signal,
    dispose: () => { clearTimeout(timer); signal?.removeEventListener("abort", abort); },
  };
}

export async function fetchJson<T>(url: string, options: RequestInit = {}, timeoutMs = 45_000): Promise<{ data: T; headers: Headers }> {
  const scope = requestScope(options.signal ?? undefined, timeoutMs);
  try {
    if (scope.signal.aborted) throw scope.signal.reason;
    let response: Response;
    try {
      response = await fetch(url, { ...options, signal: scope.signal });
    } catch (error) {
      if (scope.signal.aborted) throw scope.signal.reason;
      if (error instanceof TypeError) throw new NetworkError("Cannot reach the event server.");
      throw error;
    }
    if (!response.ok) {
      let message = `Event catalog request failed with ${response.status}`;
      try {
        const body = await response.json() as Record<string, unknown>;
        if (typeof body.message === "string") message = body.message;
      } catch { /* An HTTP error need not include JSON. */ }
      throw new HttpError(response.status, message);
    }
    // Parsing/programming errors are not relabelled as network failures.
    return { data: await response.json() as T, headers: response.headers };
  } catch (error) {
    if (scope.signal.aborted) throw scope.signal.reason;
    throw error;
  } finally { scope.dispose(); }
}

export function canUseBrowserFallback(error: unknown): boolean {
  return error instanceof NetworkError || error instanceof RequestTimeoutError
    || (error instanceof HttpError && [500, 502, 503, 504].includes(error.status));
}
