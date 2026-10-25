import { AudioLines, ArrowRight } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '@/app/auth/AuthProvider';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';

// Customized from the official shadcn login-03 block; authentication stays in OIDC.
export function LoginForm() {
  const { t } = useTranslation('admin');
  const { login } = useAuth();
  return (
    <div className="flex w-full max-w-sm flex-col gap-4">
      <Card>
        <CardHeader className="items-center gap-3 p-6 text-center">
          <div className="brand-mark">
            <AudioLines aria-hidden="true" />
          </div>
          <CardTitle id="login-form-title" className="text-xl">
            {t('login.welcome')}
          </CardTitle>
          <CardDescription>{t('login.access')}</CardDescription>
        </CardHeader>
        <CardContent className="px-6 pb-6">
          <Button className="w-full" onClick={() => void login()}>
            {t('signIn')}
            <ArrowRight data-icon="inline-end" aria-hidden="true" />
          </Button>
        </CardContent>
      </Card>
      <p className="text-balance text-center text-xs text-muted-foreground">
        {t('login.note')}
      </p>
    </div>
  );
}
