import {
  BookOpen,
  BookText,
  Layers,
  LayoutDashboard,
  ListChecks,
  Settings2,
} from 'lucide-react';
import { NavLink, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { adminNavItems } from '@/app/layout/admin-navigation';
import {
  SidebarGroup,
  SidebarGroupLabel,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from '@/components/ui/sidebar';
const icons = {
  '/': LayoutDashboard,
  '/lessons': BookOpen,
  '/curriculum': Layers,
  '/lexicon': BookText,
  '/jobs': ListChecks,
  '/settings/ai': Settings2,
};
export function NavMain() {
  const { t } = useTranslation('admin');
  const { setOpenMobile } = useSidebar();
  const { pathname } = useLocation();
  return (
    <nav aria-label={t('shell.navigation')}>
      {([undefined, 'content', 'operations'] as const).map((group) => (
        <SidebarGroup key={group ?? 'home'}>
          {group ? (
            <SidebarGroupLabel>{t(`shell.${group}`)}</SidebarGroupLabel>
          ) : null}
          <SidebarMenu>
            {adminNavItems
              .filter((item) => item.group === group)
              .map(({ path, labelKey, end }) => {
                const Icon = icons[path as keyof typeof icons];
                return (
                  <SidebarMenuItem key={path}>
                    <SidebarMenuButton
                      asChild
                      tooltip={t(labelKey)}
                      isActive={
                        end ? pathname === path : pathname.startsWith(path)
                      }
                    >
                      <NavLink
                        to={path}
                        end={end}
                        onClick={() => setOpenMobile(false)}
                        aria-label={t(labelKey)}
                      >
                        <Icon aria-hidden="true" />
                        <span>{t(labelKey)}</span>
                      </NavLink>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                );
              })}
          </SidebarMenu>
        </SidebarGroup>
      ))}
    </nav>
  );
}
