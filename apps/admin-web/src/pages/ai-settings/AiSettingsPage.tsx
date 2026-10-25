import { FormEvent, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Alert, AlertTitle, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Empty, EmptyDescription } from '@/components/ui/empty';
import { Field, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table,
  TableHeader,
  TableBody,
  TableHead,
  TableRow,
  TableCell,
} from '@/components/ui/table';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import {
  fetchAiProviders,
  fetchAiRoutes,
  saveAiProvider,
} from './ai-settings.api';
import type { Provider, Route } from './ai-settings.types';

export function AiSettingsPage() {
  const { t } = useTranslation(['admin', 'common']);
  const [providers, setProviders] = useState<Provider[]>([]);
  const [routes, setRoutes] = useState<Route[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const [providerCode, setProviderCode] = useState('GROQ');
  const [displayName, setDisplayName] = useState('Groq');
  const [baseUrl, setBaseUrl] = useState('https://api.groq.com/openai/v1');
  const [apiKey, setApiKey] = useState('');
  const [enabled, setEnabled] = useState(true);

  async function load() {
    setLoading(true);
    try {
      const [providerRows, routeRows] = await Promise.all([
        fetchAiProviders(),
        fetchAiRoutes(),
      ]);
      setProviders(providerRows);
      setRoutes(routeRows);
      setError('');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function handleSaveProvider(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError('');
    try {
      await saveAiProvider(providerCode, {
        displayName,
        baseUrl,
        apiKey: apiKey || null,
        enabled,
      });
      setApiKey('');
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section>
      <div className="eyebrow">{t('aiSettings.eyebrow')}</div>
      <h1>{t('aiSettings.title')}</h1>
      <p className="lead">{t('aiSettings.lead')}</p>

      {error ? (
        <Alert variant="destructive" className="mt-4">
          <AlertTitle>{t('aiSettings.apiError')}</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}

      <div className="settings-workspace">
        <div className="settings-tables">
          <Card className="mt-4">
            <CardHeader>
              <CardTitle>{t('aiSettings.providers')}</CardTitle>
            </CardHeader>
            <CardContent>
              {loading ? (
                <div role="status" aria-label={t('common:status.loading')}>
                  <Skeleton className="h-20" />
                </div>
              ) : (
                <div
                  className="overflow-x-auto"
                  role="region"
                  aria-label={t('aiSettings.providers')}
                  tabIndex={0}
                >
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead scope="col">
                          {t('aiSettings.table.provider')}
                        </TableHead>
                        <TableHead scope="col">
                          {t('aiSettings.table.endpoint')}
                        </TableHead>
                        <TableHead scope="col">
                          {t('aiSettings.table.credential')}
                        </TableHead>
                        <TableHead scope="col">
                          {t('aiSettings.table.status')}
                        </TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {providers.length === 0 ? (
                        <TableRow>
                          <TableCell
                            colSpan={4}
                            className="muted py-4 text-center"
                          >
                            <Empty>
                              <EmptyDescription>
                                {t('aiSettings.notConfigured')}
                              </EmptyDescription>
                            </Empty>
                          </TableCell>
                        </TableRow>
                      ) : (
                        providers.map((provider) => (
                          <TableRow key={provider.id}>
                            <TableCell>
                              <strong>{provider.code}</strong>
                              <br />
                              <span className="muted">
                                {provider.display_name}
                              </span>
                            </TableCell>
                            <TableCell>
                              {provider.base_url ||
                                t('aiSettings.internalRuntime')}
                            </TableCell>
                            <TableCell>
                              {provider.configured
                                ? `•••• ${provider.key_last4 || ''}`
                                : t('aiSettings.notConfigured')}
                            </TableCell>
                            <TableCell>
                              <Badge variant="secondary">
                                {provider.enabled
                                  ? provider.connection_status
                                  : t('aiSettings.disabled')}
                              </Badge>
                            </TableCell>
                          </TableRow>
                        ))
                      )}
                    </TableBody>
                  </Table>
                </div>
              )}
            </CardContent>
          </Card>

          <Card className="mt-4">
            <CardHeader>
              <CardTitle>{t('aiSettings.routes')}</CardTitle>
            </CardHeader>
            <CardContent>
              {loading ? (
                <div role="status" aria-label={t('common:status.loading')}>
                  <Skeleton className="h-20" />
                </div>
              ) : (
                <>
                  <div
                    className="overflow-x-auto"
                    role="region"
                    aria-label={t('aiSettings.routes')}
                    tabIndex={0}
                  >
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead scope="col">
                            {t('aiSettings.table.capability')}
                          </TableHead>
                          <TableHead scope="col">
                            {t('aiSettings.table.provider')}
                          </TableHead>
                          <TableHead scope="col">
                            {t('aiSettings.table.model')}
                          </TableHead>
                          <TableHead scope="col">
                            {t('aiSettings.table.priority')}
                          </TableHead>
                          <TableHead scope="col">
                            {t('aiSettings.table.fallback')}
                          </TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {routes.length === 0 ? (
                          <TableRow>
                            <TableCell
                              colSpan={5}
                              className="muted py-4 text-center"
                            >
                              <Empty>
                                <EmptyDescription>
                                  {t('aiSettings.notConfigured')}
                                </EmptyDescription>
                              </Empty>
                            </TableCell>
                          </TableRow>
                        ) : (
                          routes.map((route) => (
                            <TableRow key={route.id}>
                              <TableCell>{route.capability}</TableCell>
                              <TableCell>{route.provider}</TableCell>
                              <TableCell>{route.model}</TableCell>
                              <TableCell>{route.priority}</TableCell>
                              <TableCell>
                                {route.is_fallback
                                  ? t('common:yes')
                                  : t('common:no')}
                              </TableCell>
                            </TableRow>
                          ))
                        )}
                      </TableBody>
                    </Table>
                  </div>
                  <p className="muted">{t('aiSettings.modelNamesNote')}</p>
                </>
              )}
            </CardContent>
          </Card>
        </div>
        <Card className="mt-4">
          <CardHeader>
            <CardTitle>{t('aiSettings.configure')}</CardTitle>
          </CardHeader>
          <CardContent>
            <form className="form" onSubmit={handleSaveProvider}>
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="provider-code">
                    {t('aiSettings.fields.code')}
                  </FieldLabel>
                  <Input
                    id="provider-code"
                    value={providerCode}
                    onChange={(event) =>
                      setProviderCode(event.target.value.toUpperCase())
                    }
                  />
                </Field>
                <Field>
                  <FieldLabel htmlFor="provider-displayName">
                    {t('aiSettings.fields.displayName')}
                  </FieldLabel>
                  <Input
                    id="provider-displayName"
                    value={displayName}
                    onChange={(event) => setDisplayName(event.target.value)}
                  />
                </Field>
                <Field>
                  <FieldLabel htmlFor="provider-baseUrl">
                    {t('aiSettings.fields.baseUrl')}
                  </FieldLabel>
                  <Input
                    id="provider-baseUrl"
                    value={baseUrl}
                    onChange={(event) => setBaseUrl(event.target.value)}
                  />
                </Field>
                <Field>
                  <FieldLabel htmlFor="provider-apiKey">
                    {t('aiSettings.fields.apiKey')}
                  </FieldLabel>
                  <Input
                    id="provider-apiKey"
                    type="password"
                    value={apiKey}
                    onChange={(event) => setApiKey(event.target.value)}
                    placeholder={t('aiSettings.fields.apiKeyPlaceholder')}
                  />
                </Field>
                <Field orientation="horizontal">
                  <FieldLabel htmlFor="provider-enabled">
                    {t('aiSettings.fields.enabled')}
                  </FieldLabel>
                  <Switch
                    id="provider-enabled"
                    checked={enabled}
                    onCheckedChange={setEnabled}
                  />
                </Field>
              </FieldGroup>
              <Button className="my-3" disabled={saving} type="submit">
                {saving ? t('common:status.saving') : t('aiSettings.save')}
              </Button>
              <p className="muted">{t('aiSettings.encryptionNote')}</p>
            </form>
          </CardContent>
        </Card>
      </div>
    </section>
  );
}
