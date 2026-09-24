import {
  apiErrorFromRequestFailure,
  apiErrorFromResponse,
  unauthorizedApiError,
} from './errors';

export type ApiClient = {
  request: <T>(path: string, init?: RequestInit) => Promise<T>;
};

type ApiClientDependencies = {
  baseUrl: string;
  getValidAccessToken: () => Promise<string | null>;
  refreshSession: () => Promise<string | null>;
  invalidateSession: () => Promise<void>;
  fetchImplementation?: typeof fetch;
};

export function createApiClient(dependencies: ApiClientDependencies): ApiClient {
  const fetchImplementation = dependencies.fetchImplementation ?? fetch;

  async function send(path: string, token: string, init: RequestInit): Promise<Response> {
    const headers = new Headers(init.headers);
    if (!headers.has('Accept')) {
      headers.set('Accept', 'application/json, application/problem+json');
    }
    headers.set('Authorization', `Bearer ${token}`);
    if (typeof init.body === 'string' && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    try {
      return await fetchImplementation(`${dependencies.baseUrl}${normalizePath(path)}`, {
        ...init,
        headers,
      });
    } catch (error) {
      throw apiErrorFromRequestFailure(error);
    }
  }

  return {
    async request<T>(path: string, init: RequestInit = {}): Promise<T> {
      let accessToken: string | null;
      try {
        accessToken = await dependencies.getValidAccessToken();
      } catch (error) {
        throw apiErrorFromRequestFailure(error);
      }
      if (!accessToken) throw unauthorizedApiError();

      let response = await send(path, accessToken, init);
      if (response.status === 401) {
        let refreshedToken: string | null;
        try {
          refreshedToken = await dependencies.refreshSession();
        } catch (error) {
          throw apiErrorFromRequestFailure(error);
        }
        if (!refreshedToken) throw await apiErrorFromResponse(response);
        response = await send(path, refreshedToken, init);
        if (response.status === 401) {
          await dependencies.invalidateSession();
        }
      }

      if (!response.ok) throw await apiErrorFromResponse(response);
      if (response.status === 204) return undefined as T;

      const text = await response.text();
      if (!text) return undefined as T;
      try {
        return JSON.parse(text) as T;
      } catch (error) {
        throw apiErrorFromRequestFailure(error);
      }
    },
  };
}

function normalizePath(path: string): string {
  return path.startsWith('/') ? path : `/${path}`;
}
