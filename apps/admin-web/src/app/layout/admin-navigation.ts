export type AdminNavItem = {
  readonly path: string;
  readonly labelKey: string;
  readonly end?: boolean;
  readonly group?: 'content' | 'operations';
};

export const adminNavItems: readonly AdminNavItem[] = [
  { path: '/', labelKey: 'admin:nav.overview', end: true },
  { path: '/lessons', group: 'content', labelKey: 'admin:nav.lessonBuilder' },
  { path: '/curriculum', group: 'content', labelKey: 'admin:nav.curriculum' },
  { path: '/lexicon', group: 'content', labelKey: 'admin:nav.lexicon' },
  { path: '/jobs', group: 'operations', labelKey: 'admin:nav.jobs' },
  {
    path: '/settings/ai',
    group: 'operations',
    labelKey: 'admin:nav.aiProviders',
  },
] as const;

export function getNavTitleKey(pathname: string): string {
  const matched = adminNavItems.find((item) =>
    item.end ? pathname === item.path : pathname.startsWith(item.path),
  );
  return matched ? matched.labelKey : 'admin:nav.overview';
}
