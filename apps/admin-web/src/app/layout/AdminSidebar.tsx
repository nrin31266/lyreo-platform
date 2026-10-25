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
import { Link, NavLink } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { DialogContent, DialogTitle } from '@/components/ui/dialog';
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip';
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

function SidebarContent({
  collapsed = false,
  onClose,
}: {
  collapsed?: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation(['admin', 'common']);
  const { logout, user } = useAuth();
  const username =
    user?.profile?.preferred_username ??
    user?.profile?.email ??
    t('admin:shell.administrator');
  return (
    <>
      <Link
        to="/"
        className="logo"
        aria-label={`Lyreo · ${t('admin:workspace')}`}
        onClick={onClose}
      >
        <span>
          <AudioLines aria-hidden="true" />
        </span>
        <div>
          <strong>Lyreo</strong>
          <small>{t('admin:workspace')}</small>
        </div>
      </Link>
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
                const link = (
                  <NavLink
                    to={path}
                    end={end}
                    onClick={onClose}
                    aria-label={t(labelKey)}
                  >
                    <Icon aria-hidden="true" />
                    <span>{t(labelKey)}</span>
                  </NavLink>
                );
                return collapsed ? (
                  <Tooltip key={path}>
                    <TooltipTrigger asChild>{link}</TooltipTrigger>
                    <TooltipContent side="right">{t(labelKey)}</TooltipContent>
                  </Tooltip>
                ) : (
                  <div key={path}>{link}</div>
                );
              })}
          </div>
        ))}
      </nav>
      <div className="sidebar-footer">
        <div className="sidebar-account" title={username}>
          <span className="account-avatar" aria-hidden="true">
            {username.slice(0, 1).toUpperCase()}
          </span>
          <div>
            <strong>{username}</strong>
            <small>{t('admin:shell.administrator')}</small>
          </div>
        </div>
        <Button
          className="sidebar-signout w-full justify-start"
          variant="ghost"
          aria-label={t('common:actions.signOut')}
          onClick={() => void logout()}
        >
          <LogOut aria-hidden="true" className="size-4" />
          <span>{t('common:actions.signOut')}</span>
        </Button>
      </div>
    </>
  );
}

export function AdminSidebar({
  collapsed,
  onClose,
}: {
  collapsed: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation(['admin', 'common']);
  return (
    <>
      <aside
        id="desktop-navigation"
        className="admin-sidebar desktop-sidebar"
        data-collapsed={collapsed}
      >
        <SidebarContent collapsed={collapsed} onClose={onClose} />
      </aside>
      <DialogContent
        closeLabel={t('common:actions.close')}
        aria-describedby={undefined}
        className="mobile-sidebar left-0 top-0 h-dvh w-[min(17rem,90vw)] translate-x-0 translate-y-0 rounded-none border-y-0 border-l-0 p-0"
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
