export type Provider = {
  id: string;
  code: string;
  display_name: string;
  base_url?: string;
  enabled: boolean;
  connection_status: string;
  key_last4?: string;
  configured: boolean;
};

export type Route = {
  id: string;
  capability: string;
  provider: string;
  model: string;
  priority: number;
  is_fallback: boolean;
  enabled: boolean;
};

export type SaveProviderPayload = {
  displayName: string;
  baseUrl: string;
  apiKey: string | null;
  enabled: boolean;
};
