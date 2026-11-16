import axios from "axios";
import { env } from "@/config/env";
import { accessToken } from "@/lib/oidc/client";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
    readonly detail?: string,
    readonly correlationId?: string,
    readonly errors?: { field: string; code?: string; message: string }[],
  ) {
    super(message);
    this.name = "ApiError";
  }
}
export const http = axios.create({ baseURL: env.apiBaseUrl, timeout: 30_000 });
http.interceptors.request.use(async (config) => {
  const token = await accessToken();
  if (token) config.headers.set("Authorization", `Bearer ${token}`);
  return config;
});
http.interceptors.response.use(
  (response) => response,
  (cause: unknown) => {
    if (axios.isCancel(cause)) return Promise.reject(cause);
    if (!axios.isAxiosError(cause)) return Promise.reject(cause);
    const data = cause.response?.data;
    const problem = data && typeof data === "object" ? data : {};
    return Promise.reject(
      new ApiError(
        cause.response?.status ?? 0,
        typeof problem.detail === "string"
          ? problem.detail
          : "Unable to complete the request",
        problem.code,
        problem.detail,
        problem.correlationId,
        problem.errors,
      ),
    );
  },
);

// Keep the existing call contract while all features share Axios transport.
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await http.request<T>({
    url: path,
    method: init.method ?? "GET",
    data: typeof init.body === "string" ? JSON.parse(init.body) : init.body,
    headers: Object.fromEntries(new Headers(init.headers).entries()),
    signal: init.signal ?? undefined,
  });
  return response.data;
}
