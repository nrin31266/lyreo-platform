import { useEffect, useRef, useState, type PropsWithChildren } from 'react';
import { RiShieldKeyholeLine as ShieldAlert } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { useAuth } from './AuthProvider';

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
  const { isLoading, isAuthenticated, isAdmin, login } = useAuth();
  const { t } = useTranslation('admin');
  const triggered = useRef(false);
  const [loginFailed, setLoginFailed] = useState(false);

  useEffect(() => {
    if (!isLoading && !isAuthenticated && !triggered.current) {
      triggered.current = true;
      void login().catch(() => setLoginFailed(true));
    }
  }, [isLoading, isAuthenticated, login]);

  if (isLoading || (!isAuthenticated && !loginFailed)) {
    return (
      <div className="center" role="status">
        {t('loading')}
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <main className="center gap-3">
        <p className="text-sm text-muted-foreground">{t('authRedirectError')}</p>
        <Button
          onClick={() => {
            setLoginFailed(false);
            triggered.current = true;
            void login().catch(() => setLoginFailed(true));
          }}
        >
          {t('signInAction')}
        </Button>
      </main>
    );
  }

  if (!isAdmin) return <Unauthorized />;
  return <>{children}</>;
}
