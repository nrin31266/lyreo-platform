import { useTranslation } from 'react-i18next';
import { NavLink } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { useAuth } from '@/app/auth';
import { adminNavItems } from './admin-navigation';

type AdminSidebarProps = {
  readonly isOpen?: boolean;
  readonly onClose?: () => void;
};

export function AdminSidebar({ isOpen, onClose }: AdminSidebarProps) {
  const { t } = useTranslation(['admin', 'common']);
  const { logout, user } = useAuth();

  const username = user?.profile?.preferred_username ?? user?.profile?.email ?? 'ADMIN';

  return (
    <>
      {isOpen ? (
        <div
          className="sidebar-backdrop fixed inset-0 z-40 bg-overlay md:hidden"
          onClick={onClose}
          aria-hidden="true"
        />
      ) : null}

      <aside className={`admin-sidebar ${isOpen ? 'open' : ''}`}>
        <div className="logo">
          <span>◒</span>
          <div>
            <strong>Lyreo</strong>
            <small>Admin</small>
          </div>
        </div>

        <nav aria-label="Main navigation">
          {adminNavItems.map(({ path, labelKey, end }) => (
            <NavLink
              key={path}
              to={path}
              end={end}
              className={({ isActive }) => (isActive ? 'active' : '')}
              onClick={onClose}
            >
              {t(labelKey)}
            </NavLink>
          ))}
        </nav>

        <div className="sidebar-footer mt-auto flex flex-col gap-3 pt-4 border-t border-primary-foreground/20">
          <div className="flex flex-col text-xs opacity-80 px-1">
            <span className="font-semibold truncate">{username}</span>
            <span className="text-[10px] uppercase tracking-wider opacity-60">Admin role</span>
          </div>

          <Button
            className="w-full"
            variant="outline"
            onClick={() => void logout()}
          >
            {t('common:actions.signOut')}
          </Button>
        </div>
      </aside>
    </>
  );
}
