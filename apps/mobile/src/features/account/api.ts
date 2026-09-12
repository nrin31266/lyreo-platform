import type { ApiClient } from '@/api/client';

export type CurrentUser = {
  id: string;
  subject: string;
  email: string;
};

export function getCurrentUser(client: ApiClient, signal?: AbortSignal): Promise<CurrentUser> {
  return client.request<CurrentUser>('/api/v1/me', { signal });
}
