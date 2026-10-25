import { Suspense, useEffect, useState } from 'react';
import { Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Dialog } from '@/components/ui/dialog';
import { TooltipProvider } from '@/components/ui/tooltip';
import { AdminHeader } from './AdminHeader';
import { AdminSidebar } from './AdminSidebar';

export function AdminLayout() {
  const { t } = useTranslation('admin');
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(
    () => localStorage.getItem('lyreo.admin.sidebar-collapsed') === 'true',
  );

  useEffect(() => {
    localStorage.setItem('lyreo.admin.sidebar-collapsed', String(collapsed));
  }, [collapsed]);
  useEffect(() => {
    const desktop = window.matchMedia('(min-width: 768px)');
    const closeOnDesktop = () => {
      if (desktop.matches) setSidebarOpen(false);
    };
    desktop.addEventListener('change', closeOnDesktop);
    return () => desktop.removeEventListener('change', closeOnDesktop);
  }, []);

  return (
    <TooltipProvider delayDuration={250}>
      <Dialog open={sidebarOpen} onOpenChange={setSidebarOpen}>
        <div className="shell" data-collapsed={collapsed}>
          <a className="skip-link" href="#main-content">
            {t('shell.skipToContent')}
          </a>
          <AdminSidebar
            collapsed={collapsed}
            onClose={() => setSidebarOpen(false)}
          />
          <div className="content-container flex min-w-0 flex-col">
            <AdminHeader
              collapsed={collapsed}
              onToggleSidebar={() => setCollapsed((value) => !value)}
            />
            <main id="main-content" tabIndex={-1} className="flex-1">
              <Suspense fallback={<p role="status">{t('loading')}</p>}>
                <Outlet />
              </Suspense>
            </main>
          </div>
        </div>
      </Dialog>
    </TooltipProvider>
  );
}
