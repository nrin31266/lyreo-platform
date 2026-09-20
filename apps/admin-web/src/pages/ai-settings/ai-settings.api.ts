import { api } from '@/lib/api';
import type { Provider, Route, SaveProviderPayload } from './ai-settings.types';

export async function fetchAiProviders(): Promise<Provider[]> {
  return api<Provider[]>('/api/v1/admin/ai/providers');
}

export async function fetchAiRoutes(): Promise<Route[]> {
  return api<Route[]>('/api/v1/admin/ai/routes');
}

export async function saveAiProvider(code: string, payload: SaveProviderPayload): Promise<void> {
  return api<void>(`/api/v1/admin/ai/providers/${code}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}
