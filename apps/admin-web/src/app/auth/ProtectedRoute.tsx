import { lyreoBrand } from '@lyreo/design-system';
import { AudioLines, ShieldAlert } from 'lucide-react';
import type { PropsWithChildren } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { useAuth } from './AuthProvider';

export function Login() {
  const { t } = useTranslation('admin');
  const { login } = useAuth();
  return (
    <main className="login-page">
      <section className="login-intro" aria-labelledby="login-intro-title">
        <div className="logo">
          <span>
            <AudioLines aria-hidden="true" />
          </span>
          <div>
            <strong>{lyreoBrand.name}</strong>
            <small>{lyreoBrand.tagline}</small>
          </div>
        </div>
        <div>
          <h1 id="login-intro-title">{t('login.title')}</h1>
          <p>{t('login.description')}</p>
        </div>
        <div className="login-process" aria-label={t('overview.workflowTitle')}>
          <span>{t('login.source')}</span>
          <span>{t('login.review')}</span>
          <span>{t('login.publish')}</span>
        </div>
      </section>
      <section className="login-form" aria-labelledby="login-form-title">
        <div className="brand-mark">
          <AudioLines aria-hidden="true" />
        </div>
        <div>
          <h2 id="login-form-title">{t('login.welcome')}</h2>
          <p>{t('login.access')}</p>
        </div>
        <Button size="lg" onClick={() => void login()}>
          {t('signIn')}
        </Button>
        <p className="login-note">{t('login.note')}</p>
      </section>
    </main>
  );
}

export function Unauthorized() {
  const { t } = useTranslation(['admin', 'common']);
  const { logout } = useAuth();
  return (
    <main className="center">
      <div className="brand-mark">
        <ShieldAlert aria-hidden="true" />
      </div>
      <h1>{t('admin:unauthorized.title')}</h1>
      <p className="max-w-md text-muted-foreground">
        {t('admin:unauthorized.message')}
      </p>
      <Button variant="outline" onClick={() => void logout()}>
        {t('common:actions.signOut')}
      </Button>
    </main>
  );
}

export function ProtectedRoute({ children }: PropsWithChildren) {
  const { isLoading, isAuthenticated, isAdmin } = useAuth();
  const { t } = useTranslation('admin');
  if (isLoading)
    return (
      <div className="center" role="status">
        {t('loading')}
      </div>
    );
  if (!isAuthenticated) return <Login />;
  if (!isAdmin) return <Unauthorized />;
  return <>{children}</>;
}
