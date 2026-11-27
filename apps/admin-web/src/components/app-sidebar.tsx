import { RiCloseLine as X } from "@remixicon/react";
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
            <SidebarMenuButton size="lg" className="overflow-visible group-data-[collapsible=icon]:justify-center group-data-[collapsible=icon]:gap-0" asChild>
              <Link
                to="/"
                onClick={() => setOpenMobile(false)}
                aria-label={`Lyreo · ${t('admin:workspace')}`}
              >
                <img
                  src={`${import.meta.env.BASE_URL}brand/lyreo-head.png`}
                  alt=""
                  className="pointer-events-none size-8 shrink-0 scale-150 object-contain group-data-[collapsible=icon]:scale-125"
                />
                <span className="flex flex-col group-data-[collapsible=icon]:hidden">
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
