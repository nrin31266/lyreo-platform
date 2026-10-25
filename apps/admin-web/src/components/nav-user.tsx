import { LogOut } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '@/app/auth';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import {
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
} from '@/components/ui/sidebar';
export function NavUser() {
  const { t } = useTranslation(['admin', 'common']);
  const { user, logout } = useAuth();
  const name =
    user?.profile?.preferred_username ??
    user?.profile?.email ??
    t('admin:shell.administrator');
  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <SidebarMenuButton size="lg" asChild>
          <div title={name}>
            <Avatar className="size-7">
              <AvatarFallback>{name.slice(0, 1).toUpperCase()}</AvatarFallback>
            </Avatar>
            <span className="flex min-w-0 flex-col">
              <strong className="truncate">{name}</strong>
              <small className="text-muted-foreground">
                {t('admin:shell.administrator')}
              </small>
            </span>
          </div>
        </SidebarMenuButton>
      </SidebarMenuItem>
      <SidebarMenuItem>
        <SidebarMenuButton
          onClick={() => void logout()}
          tooltip={t('common:actions.signOut')}
          aria-label={t('common:actions.signOut')}
        >
          <LogOut aria-hidden="true" />
          <span>{t('common:actions.signOut')}</span>
        </SidebarMenuButton>
      </SidebarMenuItem>
    </SidebarMenu>
  );
}
