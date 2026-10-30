import { RiVoiceprintLine as AudioLines, RiCloseLine as X } from "@remixicon/react";
import { Button } from '@/components/ui/button';
import { SheetClose } from '@/components/ui/sheet';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { NavMain } from '@/components/nav-main';
import { NavUser } from '@/components/nav-user';
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuItem,
  SidebarMenuButton,
  useSidebar,
} from '@/components/ui/sidebar';

// Customized from the official shadcn sidebar-07 block.
export function AppSidebar() {
  const { t } = useTranslation(['admin', 'common']);
  const { setOpenMobile, isMobile } = useSidebar();
  return (
    <Sidebar collapsible="icon" id="desktop-navigation">
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton size="lg" asChild>
              <Link
                to="/"
                onClick={() => setOpenMobile(false)}
                aria-label={`Lyreo · ${t('admin:workspace')}`}
              >
                <span className="flex size-7 shrink-0 items-center justify-center rounded-md bg-sidebar-primary text-sidebar-primary-foreground">
                  <AudioLines aria-hidden="true" />
                </span>
                <span className="flex flex-col">
                  <strong>Lyreo</strong>
                  <small className="text-muted-foreground">
                    {t('admin:workspace')}
                  </small>
                </span>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
        {isMobile ? (
          <SheetClose asChild>
            <Button
              variant="ghost"
              size="icon"
              className="absolute right-2 top-2"
              aria-label={t('common:actions.close')}
            >
              <X aria-hidden="true" />
            </Button>
          </SheetClose>
        ) : null}
      </SidebarHeader>
      <SidebarContent>
        <NavMain />
      </SidebarContent>
      <SidebarFooter>
        <NavUser />
      </SidebarFooter>
    </Sidebar>
  );
}
