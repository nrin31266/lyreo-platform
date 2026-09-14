export type ApiErrorKind =
  | 'network'
  | 'timeout'
  | 'unauthorized'
  | 'forbidden'
  | 'validation'
  | 'conflict'
  | 'server'
  | 'unknown';

export type ApiFieldError = {
  field: string;
  code?: string;
  message: string;
};

type ApiErrorOptions = {
  kind: ApiErrorKind;
  status?: number;
  problemType?: string;
  instance?: string;
  code?: string;
  title?: string;
  detail?: string;
  fieldErrors?: ApiFieldError[];
  correlationId?: string;
  cause?: unknown;
};

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status?: number;
  readonly problemType?: string;
  readonly instance?: string;
  readonly code?: string;
  readonly title?: string;
  readonly detail?: string;
  readonly fieldErrors?: ApiFieldError[];
  readonly correlationId?: string;
  override readonly cause?: unknown;

  constructor(options: ApiErrorOptions) {
    super(options.detail ?? options.title ?? 'The request failed');
    this.name = 'ApiError';
    this.kind = options.kind;
    this.status = options.status;
    this.problemType = options.problemType;
    this.instance = options.instance;
    this.code = options.code;
    this.title = options.title;
    this.detail = options.detail;
    this.fieldErrors = options.fieldErrors;
    this.correlationId = options.correlationId;
    this.cause = options.cause;
  }
}

export async function apiErrorFromResponse(response: Response): Promise<ApiError> {
  const body = await readProblemBody(response);
  const status = response.status;
  return new ApiError({
    kind: kindForStatus(status),
    status,
    problemType: stringProperty(body, 'type'),
    instance: stringProperty(body, 'instance'),
    code: stringProperty(body, 'code'),
    title: stringProperty(body, 'title'),
    detail: stringProperty(body, 'detail') ?? stringProperty(body, 'message'),
    fieldErrors: fieldErrorsFrom(body),
    correlationId:
      response.headers.get('X-Correlation-Id')
      ?? stringProperty(body, 'correlationId'),
  });
}

export function apiErrorFromRequestFailure(cause: unknown): ApiError {
  if (cause instanceof ApiError) return cause;
  if (isAbortError(cause)) return new ApiError({ kind: 'timeout', cause });
  if (cause instanceof TypeError) return new ApiError({ kind: 'network', cause });
  return new ApiError({ kind: 'unknown', cause });
}

export function unauthorizedApiError(): ApiError {
  return new ApiError({
    kind: 'unauthorized',
    status: 401,
    code: 'AUTHENTICATION_REQUIRED',
    title: 'Authentication required',
  });
}

function kindForStatus(status: number): ApiErrorKind {
  if (status === 400) return 'validation';
  if (status === 401) return 'unauthorized';
  if (status === 403) return 'forbidden';
  if (status === 409) return 'conflict';
  if (status >= 500) return 'server';
  return 'unknown';
}

async function readProblemBody(response: Response): Promise<Record<string, unknown> | null> {
  const text = await response.text().catch(() => '');
  if (!text) return null;
  try {
    const parsed: unknown = JSON.parse(text);
    return isRecord(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function fieldErrorsFrom(body: Record<string, unknown> | null): ApiFieldError[] | undefined {
  if (!Array.isArray(body?.errors)) return undefined;
  const errors = body.errors.flatMap(item => {
    if (!isRecord(item)) return [];
    const field = stringProperty(item, 'field');
    const message = stringProperty(item, 'message');
    if (!field || !message) return [];
    return [{ field, message, code: stringProperty(item, 'code') }];
  });
  return errors.length > 0 ? errors : undefined;
}

function stringProperty(record: Record<string, unknown> | null, key: string): string | undefined {
  const value = record?.[key];
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isAbortError(value: unknown): boolean {
  return value instanceof Error && value.name === 'AbortError';
}
