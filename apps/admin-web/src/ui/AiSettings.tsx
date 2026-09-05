import { FormEvent, useEffect, useState } from 'react';
import { api } from '../api';

type Provider = {
  id: string;
  code: string;
  display_name: string;
  base_url?: string;
  enabled: boolean;
  connection_status: string;
  key_last4?: string;
  configured: boolean;
};

type Route = {
  id: string;
  capability: string;
  provider: string;
  model: string;
  priority: number;
  is_fallback: boolean;
  enabled: boolean;
};

export function AiSettings() {
  const [providers, setProviders] = useState<Provider[]>([]);
  const [routes, setRoutes] = useState<Route[]>([]);
  const [error, setError] = useState('');

  const [providerCode, setProviderCode] = useState('GROQ');
  const [displayName, setDisplayName] = useState('Groq');
  const [baseUrl, setBaseUrl] = useState('https://api.groq.com/openai/v1');
  const [apiKey, setApiKey] = useState('');

  async function load() {
    try {
      const [providerRows, routeRows] = await Promise.all([
        api<Provider[]>('/api/v1/admin/ai/providers'),
        api<Route[]>('/api/v1/admin/ai/routes'),
      ]);
      setProviders(providerRows);
      setRoutes(routeRows);
      setError('');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function saveProvider(event: FormEvent) {
    event.preventDefault();
    try {
      await api(`/api/v1/admin/ai/providers/${providerCode}`, {
        method: 'PUT',
        body: JSON.stringify({
          displayName,
          baseUrl,
          apiKey: apiKey || null,
          enabled: true,
        }),
      });
      setApiKey('');
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }

  return (
    <section>
      <div className="eyebrow">Settings / AI</div>
      <h1>Provider routing</h1>
      <p className="lead">
        Provider/model is runtime data. Product prompts stay in Java business modules; plaintext
        provider API keys are never returned to the browser.
      </p>

      {error ? (
        <div className="card">
          <strong>API error</strong>
          <p className="muted">{error}</p>
        </div>
      ) : null}

      <div className="card">
        <h2>Providers</h2>
        <table>
          <thead>
            <tr>
              <th>Provider</th>
              <th>Endpoint</th>
              <th>Credential</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {providers.map(provider => (
              <tr key={provider.id}>
                <td>
                  <strong>{provider.code}</strong>
                  <br />
                  <span className="muted">{provider.display_name}</span>
                </td>
                <td>{provider.base_url || 'internal/local runtime'}</td>
                <td>
                  {provider.configured ? `•••• ${provider.key_last4 || ''}` : 'not configured'}
                </td>
                <td>{provider.enabled ? provider.connection_status : 'disabled'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="card">
        <h2>Capability routes</h2>
        <table>
          <thead>
            <tr>
              <th>Capability</th>
              <th>Provider</th>
              <th>Model</th>
              <th>Priority</th>
              <th>Fallback</th>
            </tr>
          </thead>
          <tbody>
            {routes.map(route => (
              <tr key={route.id}>
                <td>{route.capability}</td>
                <td>{route.provider}</td>
                <td>{route.model}</td>
                <td>{route.priority}</td>
                <td>{route.is_fallback ? 'Yes' : 'No'}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="muted">
          Model names remain strings by design because provider model catalogs change frequently.
        </p>
      </div>

      <form className="card form" onSubmit={saveProvider}>
        <h2>Configure / replace provider credential</h2>
        <div className="form-grid">
          <label>
            Code
            <input
              value={providerCode}
              onChange={event => setProviderCode(event.target.value.toUpperCase())}
            />
          </label>
          <label>
            Display name
            <input value={displayName} onChange={event => setDisplayName(event.target.value)} />
          </label>
          <label className="wide">
            Base URL
            <input value={baseUrl} onChange={event => setBaseUrl(event.target.value)} />
          </label>
          <label className="wide">
            New API key
            <input
              type="password"
              value={apiKey}
              onChange={event => setApiKey(event.target.value)}
              placeholder="Leave blank to keep existing secret"
            />
          </label>
        </div>
        <button type="submit">Save provider</button>
        <p className="muted">
          The master encryption key stays outside the database. Provider secrets are AES-GCM
          encrypted at rest.
        </p>
      </form>
    </section>
  );
}
