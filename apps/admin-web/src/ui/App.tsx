import { lyreoBrand, type ThemePreference } from '@lyreo/design-system';
import { normalizeLocale, supportedLocales, type SupportedLocale } from '@lyreo/i18n';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { userManager } from '../auth';
import { Button } from '../components/ui/button';
import { Card, CardContent } from '../components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select';
import { persistAdminLocale } from '../i18n';
import { useAppTheme } from '../providers/AppThemeProvider';
import { AiSettings } from './AiSettings';
import { Jobs } from './Jobs';
import { LessonBuilder } from './LessonBuilder';
import { Overview } from './Overview';

function Callback() {
  const navigate = useNavigate();
  const { t } = useTranslation('admin');

  useEffect(() => {
    userManager.signinRedirectCallback().then(() => navigate('/'));
  }, [navigate]);

  return <div className="center">{t('completingSignIn')}</div>;
}

function Login() {
  const { t } = useTranslation('admin');
  return (
    <div className="login">
      <div className="brand-mark">〰</div>
      <h1>{lyreoBrand.name}</h1>
      <p>{t('workspace')} · {lyreoBrand.tagline}</p>
      <Button variant="secondary" size="lg" onClick={() => void userManager.signinRedirect()}>
        {t('signIn')}
      </Button>
    </div>
  );
}

function Shell() {
  const location = useLocation();
  const { t, i18n } = useTranslation(['admin', 'common']);
  const { preference, setPreference } = useAppTheme();
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);

  useEffect(() => {
    userManager.getUser().then(user => setAuthenticated(Boolean(user && !user.expired)));
  }, []);

  if (authenticated === null) return <div className="center">{t('admin:loading')}</div>;
  if (!authenticated) return <Login />;

  const links = [
    ['/', t('admin:nav.overview')],
    ['/lessons/new', t('admin:nav.lessonBuilder')],
    ['/jobs', t('admin:nav.jobs')],
    ['/settings/ai', t('admin:nav.aiProviders')],
    ['/curriculum', t('admin:nav.curriculum')],
    ['/lexicon', t('admin:nav.lexicon')],
  ] as const;

  const locale = normalizeLocale(i18n.language);

  return (
    <div className="shell">
      <aside>
        <div className="logo">
          <span>◒</span>
          <div>
            <strong>Lyreo</strong>
            <small>Admin</small>
          </div>
        </div>

        <nav>
          {links.map(([to, label]) => (
            <Link className={location.pathname === to ? 'active' : ''} to={to} key={to}>
              {label}
            </Link>
          ))}
        </nav>

        <div className="appearance-controls">
          <label>
            <span>{t('admin:appearance.language')}</span>
            <Select
              value={locale}
              onValueChange={value => void persistAdminLocale(value as SupportedLocale)}
            >
              <SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
              <SelectContent>
                {supportedLocales.map(item => (
                  <SelectItem value={item} key={item}>
                    {t(`common:language.${item === 'en' ? 'english' : 'vietnamese'}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>

          <label>
            <span>{t('admin:appearance.theme')}</span>
            <Select value={preference} onValueChange={value => setPreference(value as ThemePreference)}>
              <SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
              <SelectContent>
                {(['system', 'light', 'dark'] as ThemePreference[]).map(item => (
                  <SelectItem value={item} key={item}>{t(`common:theme.${item}`)}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>
        </div>

        <Button className="mt-auto w-full" variant="outline" onClick={() => void userManager.signoutRedirect()}>
          {t('common:actions.signOut')}
        </Button>
      </aside>

      <main>
        <Routes>
          <Route path="/" element={<Overview />} />
          <Route path="/lessons/new" element={<LessonBuilder />} />
          <Route path="/jobs" element={<Jobs />} />
          <Route path="/settings/ai" element={<AiSettings />} />
          <Route
            path="/curriculum"
            element={(
              <Placeholder
                title={t('admin:placeholders.curriculum.title')}
                text={t('admin:placeholders.curriculum.text')}
              />
            )}
          />
          <Route
            path="/lexicon"
            element={(
              <Placeholder
                title={t('admin:placeholders.lexicon.title')}
                text={t('admin:placeholders.lexicon.text')}
              />
            )}
          />
          <Route path="*" element={<Navigate to="/" />} />
        </Routes>
      </main>
    </div>
  );
}

function Placeholder({ title, text }: { title: string; text: string }) {
  const { t } = useTranslation('admin');
  return (
    <section>
      <div className="eyebrow">{t('placeholders.eyebrow')}</div>
      <h1>{title}</h1>
      <Card className="mt-6">
        <CardContent className="pt-6">
          <p>{text}</p>
          <p className="muted">{t('placeholders.boundary')}</p>
        </CardContent>
      </Card>
    </section>
  );
}

export function App() {
  return (
    <Routes>
      <Route path="/auth/callback" element={<Callback />} />
      <Route path="/*" element={<Shell />} />
    </Routes>
  );
}
