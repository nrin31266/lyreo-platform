import type { ThemePreference } from '@lyreo/design-system';
import {
  normalizeLocale,
  supportedLocales,
  type SupportedLocale,
} from '@lyreo/i18n';
import { Menu } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { DialogTrigger } from '@/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { persistAdminLocale } from '@/i18n';
import { useAppTheme } from '@/app/providers/AppThemeProvider';
import { getNavTitleKey } from './admin-navigation';

export function AdminHeader() {
  const location = useLocation();
  const { t, i18n } = useTranslation(['admin', 'common']);
  const { preference, setPreference } = useAppTheme();
  const locale = normalizeLocale(i18n.language);

  return (
    <header className="admin-header">
      <div className="flex min-w-0 items-center gap-3">
        <DialogTrigger asChild>
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="md:hidden"
            aria-label={t('admin:shell.openNavigation')}
          >
            <Menu aria-hidden="true" className="size-5" />
          </Button>
        </DialogTrigger>
        <span className="header-title">
          {t(getNavTitleKey(location.pathname))}
        </span>
      </div>
      <div className="appearance-controls">
        <Select
          value={locale}
          onValueChange={(value) =>
            void persistAdminLocale(value as SupportedLocale)
          }
        >
          <SelectTrigger
            aria-label={t('admin:appearance.language')}
            className="w-[8rem] max-w-full"
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              {supportedLocales.map((item) => (
                <SelectItem value={item} key={item}>
                  {t(
                    `common:language.${item === 'en' ? 'english' : 'vietnamese'}`,
                  )}
                </SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>
        <Select
          value={preference}
          onValueChange={(value) => setPreference(value as ThemePreference)}
        >
          <SelectTrigger
            aria-label={t('admin:appearance.theme')}
            className="w-[9.25rem] max-w-full"
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              {(['system', 'light', 'dark'] as const).map((item) => (
                <SelectItem value={item} key={item}>
                  {t(`common:theme.${item}`)}
                </SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>
      </div>
    </header>
  );
}
