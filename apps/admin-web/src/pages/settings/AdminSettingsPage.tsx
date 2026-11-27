import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import {
  RiComputerLine,
  RiCpuLine,
  RiGlobalLine,
  RiMoonLine,
  RiSettings4Line,
  RiSunLine,
} from '@remixicon/react';
import { useAppTheme, type ThemePreference } from '@/app/providers/AppThemeProvider';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { AiSettingsPage } from '@/pages/ai-settings/AiSettingsPage';

type SettingsTab = 'ai' | 'preferences';

export function AdminSettingsPage() {
  const { t, i18n } = useTranslation('admin');
  const { preference, setPreference } = useAppTheme();
  const [params, setParams] = useSearchParams();

  const tabParam = params.get('tab');
  const activeTab: SettingsTab = tabParam === 'preferences' ? 'preferences' : 'ai';
  const currentLang = i18n.resolvedLanguage?.startsWith('vi') ? 'vi' : 'en';

  function selectTab(next: string) {
    const updated = new URLSearchParams(params);
    if (next === 'ai') updated.delete('tab');
    else updated.set('tab', next);
    setParams(updated, { replace: true });
  }

  const themeOptions: Array<{
    value: ThemePreference;
    labelKey: string;
    icon: typeof RiSunLine;
  }> = [
    { value: 'light', labelKey: 'settingsHub.themes.light', icon: RiSunLine },
    { value: 'dark', labelKey: 'settingsHub.themes.dark', icon: RiMoonLine },
    { value: 'system', labelKey: 'settingsHub.themes.system', icon: RiComputerLine },
  ];

  return (
    <section className="grid w-full min-w-0 gap-5">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">{t('settingsHub.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('settingsHub.subtitle')}</p>
      </header>

      <Tabs value={activeTab} onValueChange={selectTab}>
        <TabsList>
          <TabsTrigger value="ai" className="gap-1.5">
            <RiCpuLine className="size-4" aria-hidden="true" />
            {t('settingsHub.tabs.ai')}
          </TabsTrigger>
          <TabsTrigger value="preferences" className="gap-1.5">
            <RiSettings4Line className="size-4" aria-hidden="true" />
            {t('settingsHub.tabs.preferences')}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="ai" className="mt-4">
          <AiSettingsPage />
        </TabsContent>

        <TabsContent value="preferences" className="mt-4">
          <div className="divide-y rounded-xl border bg-card">
            <div className="flex flex-wrap items-center justify-between gap-4 px-5 py-4">
              <div className="space-y-0.5">
                <p className="flex items-center gap-2 text-sm font-medium">
                  <RiGlobalLine className="size-4 text-muted-foreground" aria-hidden="true" />
                  {t('settingsHub.languageTitle')}
                </p>
                <p className="text-xs text-muted-foreground">{t('settingsHub.languageHelp')}</p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button
                  type="button"
                  size="sm"
                  variant={currentLang === 'vi' ? 'default' : 'outline'}
                  onClick={() => void i18n.changeLanguage('vi')}
                >
                  Tiếng Việt (VI)
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={currentLang === 'en' ? 'default' : 'outline'}
                  onClick={() => void i18n.changeLanguage('en')}
                >
                  English (EN)
                </Button>
              </div>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-4 px-5 py-4">
              <div className="space-y-0.5">
                <p className="text-sm font-medium">{t('settingsHub.themeTitle')}</p>
                <p className="text-xs text-muted-foreground">{t('settingsHub.themeHelp')}</p>
              </div>
              <div className="flex flex-wrap gap-2">
                {themeOptions.map(({ value, labelKey, icon: Icon }) => (
                  <Button
                    key={value}
                    type="button"
                    size="sm"
                    variant={preference === value ? 'default' : 'outline'}
                    onClick={() => setPreference(value)}
                  >
                    <Icon data-icon="inline-start" aria-hidden="true" />
                    {t(labelKey)}
                  </Button>
                ))}
              </div>
            </div>
          </div>
        </TabsContent>
      </Tabs>
    </section>
  );
}
