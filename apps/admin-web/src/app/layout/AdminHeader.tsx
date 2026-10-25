import type { ThemePreference } from '@lyreo/design-system';
import {
  normalizeLocale,
  supportedLocales,
  type SupportedLocale,
} from '@lyreo/i18n';
import { Sun, Moon, Monitor } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router-dom';
import { SidebarTrigger, useSidebar } from '@/components/ui/sidebar';
import { Separator } from '@/components/ui/separator';
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
  const { open, openMobile, isMobile } = useSidebar();
  const collapsed = !open;
  const location = useLocation();
  const { t, i18n } = useTranslation(['admin', 'common']);
  const { preference, setPreference } = useAppTheme();
  const locale = normalizeLocale(i18n.language);

  return (
    <header className="admin-header">
      <div className="flex min-w-0 items-center gap-2">
        <SidebarTrigger
          aria-label={t(
            isMobile
              ? 'admin:shell.openNavigation'
              : collapsed
                ? 'admin:shell.expandSidebar'
                : 'admin:shell.collapseSidebar',
          )}
          aria-controls="desktop-navigation"
          aria-expanded={isMobile ? openMobile : open}
        />
        <Separator orientation="vertical" className="h-4" />
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
            className="h-8 w-[4.5rem] max-w-full border-transparent bg-transparent"
          >
            <SelectValue>{locale.toUpperCase()}</SelectValue>
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
            className="h-8 w-[3.5rem] max-w-full border-transparent bg-transparent"
          >
            <SelectValue>
              <span className="inline-flex items-center">
                <span className="sr-only">
                  {t(`common:theme.${preference}`)}
                </span>
                {preference === 'dark' ? (
                  <Moon className="size-4" aria-hidden="true" />
                ) : preference === 'light' ? (
                  <Sun className="size-4" aria-hidden="true" />
                ) : (
                  <Monitor className="size-4" aria-hidden="true" />
                )}
              </span>
            </SelectValue>
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
