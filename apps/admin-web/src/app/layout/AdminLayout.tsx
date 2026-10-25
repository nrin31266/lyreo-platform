import { Suspense, useEffect, useState, type CSSProperties } from 'react';
import { Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { SidebarProvider, SidebarInset } from '@/components/ui/sidebar';
import { Skeleton } from '@/components/ui/skeleton';
import { AdminHeader } from './AdminHeader';
import { AdminSidebar } from './AdminSidebar';
export function AdminLayout() {
  const { t } = useTranslation('admin');
  const [open, setOpen] = useState(
    () => localStorage.getItem('lyreo.admin.sidebar-collapsed') !== 'true',
  );
  useEffect(() => {
    localStorage.setItem('lyreo.admin.sidebar-collapsed', String(!open));
  }, [open]);
  return (
    <SidebarProvider
      open={open}
      onOpenChange={setOpen}
      style={
        {
          '--sidebar-width': '216px',
          '--sidebar-width-icon': '48px',
        } as CSSProperties
      }
    >
      <a className="skip-link" href="#main-content">
        {t('shell.skipToContent')}
      </a>
      <AdminSidebar />
      <SidebarInset className="min-w-0 overflow-clip">
        <AdminHeader />
        <div
          id="main-content"
          tabIndex={-1}
          className="min-w-0 flex-1 p-3 md:p-4"
        >
          <Suspense
            fallback={
              <div role="status" aria-label={t('loading')}>
                <Skeleton className="h-32 w-full" />
              </div>
            }
          >
            <Outlet />
          </Suspense>
        </div>
      </SidebarInset>
    </SidebarProvider>
  );
}
