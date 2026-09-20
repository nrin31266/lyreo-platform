import { lyreoBrand } from '@lyreo/design-system';
import type { PropsWithChildren } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { useAuth } from './AuthProvider';

export function Login() {
  const { t } = useTranslation('admin');
  const { login } = useAuth();
  return (
    <div className="login">
      <div className="brand-mark">〰</div>
      <h1>{lyreoBrand.name}</h1>
      <p>
        {t('workspace')} · {lyreoBrand.tagline}
      </p>
      <Button variant="secondary" size="lg" onClick={() => void login()}>
        {t('signIn')}
      </Button>
    </div>
  );
}

export function Unauthorized() {
  const { t } = useTranslation(['admin', 'common']);
  const { logout } = useAuth();
  return (
    <div className="center gap-4 p-6">
      <div className="brand-mark">⚠</div>
      <h1 className="text-2xl font-bold">{t('admin:unauthorized.title')}</h1>
      <p className="max-w-md opacity-80">{t('admin:unauthorized.message')}</p>
      <Button variant="secondary" onClick={() => void logout()}>
        {t('common:actions.signOut')}
      </Button>
    </div>
  );
}

export function ProtectedRoute({ children }: PropsWithChildren) {
  const { isLoading, isAuthenticated, isAdmin } = useAuth();
  const { t } = useTranslation('admin');

  if (isLoading) {
    return <div className="center">{t('loading')}</div>;
  }

  if (!isAuthenticated) {
    return <Login />;
  }

  if (!isAdmin) {
    return <Unauthorized />;
  }

  return <>{children}</>;
}
