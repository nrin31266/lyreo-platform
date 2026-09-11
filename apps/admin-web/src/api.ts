import { accessToken } from './auth';
import { env } from './env';
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = await accessToken();
  const response = await fetch(`${env.apiBaseUrl}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...init.headers },
  });
  if (!response.ok) throw new Error(`API ${response.status}: ${await response.text()}`);
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}
