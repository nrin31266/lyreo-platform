import { env } from '@/config/env';
import { accessToken } from '@/lib/oidc/client';

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly detail?: string;

  constructor(status: number, message: string, code?: string, detail?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = detail;
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = await accessToken();
  const response = await fetch(`${env.apiBaseUrl}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  });

  if (!response.ok) {
    const rawText = await response.text().catch(() => '');
    let message = `API request failed with status ${response.status}`;
    let code: string | undefined;
    let detail: string | undefined;

    if (rawText) {
      try {
        const data = JSON.parse(rawText) as {
          detail?: unknown;
          title?: unknown;
          code?: unknown;
          message?: unknown;
        };
        if (typeof data === 'object' && data !== null) {
          if (typeof data.code === 'string') {
            code = data.code;
          }
          if (typeof data.detail === 'string') {
            detail = data.detail;
            message = data.detail;
          } else if (typeof data.title === 'string') {
            message = data.title;
          } else if (typeof data.message === 'string') {
            message = data.message;
          }
        }
      } catch {
        message = rawText;
      }
    }

    throw new ApiError(response.status, message, code, detail);
  }

  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}
