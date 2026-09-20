import type { ThemePreference } from '@lyreo/design-system';
import { normalizeLocale, supportedLocales, type SupportedLocale } from '@lyreo/i18n';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { persistAdminLocale } from '@/i18n';
import { useAppTheme } from '@/app/providers/AppThemeProvider';
import { getNavTitleKey } from './admin-navigation';

type AdminHeaderProps = {
  readonly onToggleSidebar?: () => void;
  readonly isSidebarOpen?: boolean;
};

export function AdminHeader({ onToggleSidebar, isSidebarOpen }: AdminHeaderProps) {
  const location = useLocation();
  const { t, i18n } = useTranslation(['admin', 'common']);
  const { preference, setPreference } = useAppTheme();

  const titleKey = getNavTitleKey(location.pathname);
  const locale = normalizeLocale(i18n.language);

  return (
    <header className="admin-header">
      <div className="flex items-center gap-3">
        {onToggleSidebar ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="md:hidden"
            onClick={onToggleSidebar}
            aria-label="Navigation menu"
            aria-expanded={isSidebarOpen}
          >
            ☰
          </Button>
        ) : null}
        <span className="text-sm font-semibold tracking-wide uppercase text-muted-foreground">
          {t(titleKey)}
        </span>
      </div>

      <div className="flex items-center gap-3">
        <label className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider">
          <span className="sr-only">{t('admin:appearance.language')}</span>
          <Select
            value={locale}
            onValueChange={value => void persistAdminLocale(value as SupportedLocale)}
          >
            <SelectTrigger className="w-[120px] h-9">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {supportedLocales.map(item => (
                <SelectItem value={item} key={item}>
                  {t(`common:language.${item === 'en' ? 'english' : 'vietnamese'}`)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </label>

        <label className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider">
          <span className="sr-only">{t('admin:appearance.theme')}</span>
          <Select
            value={preference}
            onValueChange={value => setPreference(value as ThemePreference)}
          >
            <SelectTrigger className="w-[110px] h-9">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {(['system', 'light', 'dark'] as ThemePreference[]).map(item => (
                <SelectItem value={item} key={item}>
                  {t(`common:theme.${item}`)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </label>
      </div>
    </header>
  );
}
