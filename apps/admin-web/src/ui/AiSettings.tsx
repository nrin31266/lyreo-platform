import { FormEvent, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Switch } from '../components/ui/switch';

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
  const { t } = useTranslation('admin');
  const [providers, setProviders] = useState<Provider[]>([]);
  const [routes, setRoutes] = useState<Route[]>([]);
  const [error, setError] = useState('');

  const [providerCode, setProviderCode] = useState('GROQ');
  const [displayName, setDisplayName] = useState('Groq');
  const [baseUrl, setBaseUrl] = useState('https://api.groq.com/openai/v1');
  const [apiKey, setApiKey] = useState('');
  const [enabled, setEnabled] = useState(true);

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
          enabled,
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
      <div className="eyebrow">{t('aiSettings.eyebrow')}</div>
      <h1>{t('aiSettings.title')}</h1>
      <p className="lead">{t('aiSettings.lead')}</p>

      {error ? (
        <Card className="mt-6">
          <CardHeader><CardTitle>{t('aiSettings.apiError')}</CardTitle></CardHeader>
          <CardContent><p className="muted">{error}</p></CardContent>
        </Card>
      ) : null}

      <Card className="mt-6">
        <CardHeader><CardTitle>{t('aiSettings.providers')}</CardTitle></CardHeader>
        <CardContent>
          <table>
            <thead>
              <tr>
                <th>{t('aiSettings.table.provider')}</th>
                <th>{t('aiSettings.table.endpoint')}</th>
                <th>{t('aiSettings.table.credential')}</th>
                <th>{t('aiSettings.table.status')}</th>
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
                  <td>{provider.base_url || t('aiSettings.internalRuntime')}</td>
                  <td>
                    {provider.configured
                      ? `•••• ${provider.key_last4 || ''}`
                      : t('aiSettings.notConfigured')}
                  </td>
                  <td>{provider.enabled ? provider.connection_status : t('aiSettings.disabled')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardHeader><CardTitle>{t('aiSettings.routes')}</CardTitle></CardHeader>
        <CardContent>
          <table>
            <thead>
              <tr>
                <th>{t('aiSettings.table.capability')}</th>
                <th>{t('aiSettings.table.provider')}</th>
                <th>{t('aiSettings.table.model')}</th>
                <th>{t('aiSettings.table.priority')}</th>
                <th>{t('aiSettings.table.fallback')}</th>
              </tr>
            </thead>
            <tbody>
              {routes.map(route => (
                <tr key={route.id}>
                  <td>{route.capability}</td>
                  <td>{route.provider}</td>
                  <td>{route.model}</td>
                  <td>{route.priority}</td>
                  <td>{route.is_fallback ? t('common:yes') : t('common:no')}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="muted">{t('aiSettings.modelNamesNote')}</p>
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardHeader><CardTitle>{t('aiSettings.configure')}</CardTitle></CardHeader>
        <CardContent>
          <form className="form" onSubmit={saveProvider}>
            <div className="form-grid">
              <label>
                {t('aiSettings.fields.code')}
                <Input value={providerCode} onChange={event => setProviderCode(event.target.value.toUpperCase())} />
              </label>
              <label>
                {t('aiSettings.fields.displayName')}
                <Input value={displayName} onChange={event => setDisplayName(event.target.value)} />
              </label>
              <label className="wide">
                {t('aiSettings.fields.baseUrl')}
                <Input value={baseUrl} onChange={event => setBaseUrl(event.target.value)} />
              </label>
              <label className="wide">
                {t('aiSettings.fields.apiKey')}
                <Input
                  type="password"
                  value={apiKey}
                  onChange={event => setApiKey(event.target.value)}
                  placeholder={t('aiSettings.fields.apiKeyPlaceholder')}
                />
              </label>
              <label className="wide flex-row items-center justify-between">
                <span>{t('aiSettings.fields.enabled')}</span>
                <Switch checked={enabled} onCheckedChange={setEnabled} />
              </label>
            </div>
            <Button className="mt-[22px]" type="submit">{t('aiSettings.save')}</Button>
            <p className="muted">{t('aiSettings.encryptionNote')}</p>
          </form>
        </CardContent>
      </Card>
    </section>
  );
}
