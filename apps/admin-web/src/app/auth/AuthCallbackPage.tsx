import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { userManager } from '@/lib/oidc/client';
import { Button } from '@/components/ui/button';

export function AuthCallbackPage() {
  const navigate = useNavigate();
  const { t } = useTranslation('admin');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    userManager
      .signinRedirectCallback()
      .then(() => {
        if (active) navigate('/');
      })
      .catch((cause: unknown) => {
        if (active) {
          setError(cause instanceof Error ? cause.message : t('signInFailed'));
        }
      });
    return () => {
      active = false;
    };
  }, [navigate, t]);

  if (error) {
    return (
      <div className="center gap-4 p-6">
        <p className="danger">{error}</p>
        <Button variant="secondary" onClick={() => void userManager.signinRedirect()}>
          {t('signIn')}
        </Button>
      </div>
    );
  }

  return <div className="center">{t('completingSignIn')}</div>;
}
