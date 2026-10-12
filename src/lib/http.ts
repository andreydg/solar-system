export class HttpError extends Error {
  constructor(public readonly status: number, message: string) { super(message); }
}

export function requestSignal(signal?: AbortSignal, timeoutMs = 30_000): AbortSignal {
  const timeout = AbortSignal.timeout(timeoutMs);
  return signal ? AbortSignal.any([signal, timeout]) : timeout;
}

export function canUseBrowserFallback(error: unknown): boolean {
  return error instanceof TypeError || (error instanceof HttpError && [502, 503, 504].includes(error.status));
}
