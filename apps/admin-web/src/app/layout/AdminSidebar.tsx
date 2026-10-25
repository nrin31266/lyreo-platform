import {
  AudioLines,
  BookOpen,
  BookText,
  Layers,
  LayoutDashboard,
  ListChecks,
  LogOut,
  Settings2,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { NavLink } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { DialogContent, DialogTitle } from '@/components/ui/dialog';
import { useAuth } from '@/app/auth';
import { adminNavItems } from './admin-navigation';

const icons = {
  '/': LayoutDashboard,
  '/lessons': BookOpen,
  '/curriculum': Layers,
  '/lexicon': BookText,
  '/jobs': ListChecks,
  '/settings/ai': Settings2,
};

function SidebarContent({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation(['admin', 'common']);
  const { logout, user } = useAuth();
  const username =
    user?.profile?.preferred_username ??
    user?.profile?.email ??
    t('admin:shell.administrator');

  return (
    <>
      <div className="logo">
        <span>
          <AudioLines aria-hidden="true" />
        </span>
        <div>
          <strong>Lyreo</strong>
          <small>{t('admin:workspace')}</small>
        </div>
      </div>
      <nav aria-label={t('admin:shell.navigation')}>
        {([undefined, 'content', 'operations'] as const).map((group) => (
          <div className="nav-group" key={group ?? 'home'}>
            {group ? (
              <p className="nav-group-label">{t(`admin:shell.${group}`)}</p>
            ) : null}
            {adminNavItems
              .filter((item) => item.group === group)
              .map(({ path, labelKey, end }) => {
                const Icon = icons[path as keyof typeof icons];
                return (
                  <NavLink key={path} to={path} end={end} onClick={onClose}>
                    <Icon aria-hidden="true" />
                    <span>{t(labelKey)}</span>
                  </NavLink>
                );
              })}
          </div>
        ))}
      </nav>
      <div className="sidebar-footer">
        <div className="sidebar-account">
          <span className="account-avatar" aria-hidden="true">
            {username.slice(0, 1).toUpperCase()}
          </span>
          <div>
            <strong>{username}</strong>
            <small>{t('admin:shell.administrator')}</small>
          </div>
        </div>
        <Button
          className="w-full justify-start"
          variant="ghost"
          onClick={() => void logout()}
        >
          <LogOut aria-hidden="true" className="size-4" />
          {t('common:actions.signOut')}
        </Button>
      </div>
    </>
  );
}

export function AdminSidebar({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation(['admin', 'common']);
  return (
    <>
      <aside className="admin-sidebar desktop-sidebar">
        <SidebarContent onClose={onClose} />
      </aside>
      <DialogContent
        closeLabel={t('common:actions.close')}
        aria-describedby={undefined}
        className="mobile-sidebar left-0 top-0 h-dvh w-[min(20rem,90vw)] translate-x-0 translate-y-0 rounded-none border-y-0 border-l-0 p-0"
      >
        <DialogTitle className="sr-only">
          {t('admin:shell.navigation')}
        </DialogTitle>
        <div className="admin-sidebar">
          <SidebarContent onClose={onClose} />
        </div>
      </DialogContent>
    </>
  );
}
