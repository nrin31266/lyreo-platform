export type AdminNavItem = {
  readonly path: string;
  readonly labelKey: string;
  readonly end?: boolean;
};

export const adminNavItems: readonly AdminNavItem[] = [
  { path: '/', labelKey: 'admin:nav.overview', end: true },
  { path: '/lessons/new', labelKey: 'admin:nav.lessonBuilder' },
  { path: '/jobs', labelKey: 'admin:nav.jobs' },
  { path: '/settings/ai', labelKey: 'admin:nav.aiProviders' },
  { path: '/curriculum', labelKey: 'admin:nav.curriculum' },
  { path: '/lexicon', labelKey: 'admin:nav.lexicon' },
] as const;

export function getNavTitleKey(pathname: string): string {
  const matched = adminNavItems.find(item =>
    item.end ? pathname === item.path : pathname.startsWith(item.path),
  );
  return matched ? matched.labelKey : 'admin:nav.overview';
}
