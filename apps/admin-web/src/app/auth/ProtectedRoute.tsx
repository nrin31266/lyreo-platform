import { RiShieldKeyholeLine as ShieldAlert } from "@remixicon/react";
import type { PropsWithChildren } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { LoginForm } from '@/components/login-form';
import { useAuth } from './AuthProvider';

export function Login() {
  return (
    <main className="login-page">
      <LoginForm />
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
