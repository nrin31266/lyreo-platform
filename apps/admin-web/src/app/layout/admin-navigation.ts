import { RiBookOpenLine as BookOpen, RiBroadcastLine as Radio, RiBookShelfLine as Library, RiStackLine as Layers, RiGroupLine as Users, RiLineChartLine as ChartNoAxesCombined, RiTrophyLine as Trophy, RiNotification3Line as Bell, RiDiscussLine as MessagesSquare, RiListCheck3 as ListChecks, RiDatabase2Line as Database, RiImage2Line as Images, RiEqualizerLine as Settings2, RiDashboardLine as LayoutDashboard } from "@remixicon/react";
import type { RemixiconComponentType } from "@remixicon/react";
export type AdminNavItem = {
  key: string;
  icon: RemixiconComponentType;
  path?: string;
  children?: { key: string; path?: string }[];
};
export const adminNavGroups: { key: string; items: AdminNavItem[] }[] = [
  {
    key: "general",
    items: [{ key: "overview", icon: LayoutDashboard, path: "/" }],
  },
  {
    key: "content",
    items: [
      {
        key: "lessons",
        icon: BookOpen,
        children: [
          { key: "lessonList", path: "/lessons" },
          { key: "createLesson", path: "/lessons/new" },
          { key: "categories", path: "/lessons/categories" },
        ],
      },
      {
        key: "sources",
        icon: Radio,
        children: [{ key: "channels", path: "/lessons/channels" }],
      },
      {
        key: "languageLibrary",
        icon: Library,
        children: [{ key: "lexicon" }, { key: "grammar" }, { key: "toeic" }],
      },
    ],
  },
  {
    key: "learning",
    items: [
      { key: "curriculum", icon: Layers },
      { key: "activities", icon: BookOpen },
    ],
  },
  {
    key: "learners",
    items: [
      { key: "profiles", icon: Users },
      { key: "progress", icon: ChartNoAxesCombined },
    ],
  },
  {
    key: "engagement",
    items: [
      { key: "rewards", icon: Trophy },
      { key: "notifications", icon: Bell },
      { key: "conversations", icon: MessagesSquare },
    ],
  },
  {
    key: "operations",
    items: [
      { key: "jobs", icon: ListChecks, path: "/jobs" },
      { key: "imports", icon: Database },
      { key: "media", icon: Images },
    ],
  },
  {
    key: "configuration",
    items: [
      { key: "aiSettings", icon: Settings2, path: "/settings/ai" },
    ],
  },
];
export const adminNavItems = adminNavGroups
  .flatMap((g) => g.items.flatMap((i) => i.children ?? [i]))
  .filter((i) => i.path)
  .map((i) => ({ path: i.path!, labelKey: `admin:navigation.${i.key}` }));
export function getNavTitleKey(pathname: string, hash = ""): string {
  const exact = adminNavItems.find((item) => item.path === pathname + hash);
  if (exact) return exact.labelKey;
  const matched = [...adminNavItems]
    .sort((a, b) => b.path.length - a.path.length)
    .find(
      (i) =>
        pathname === i.path.split("#")[0] ||
        (i.path === "/lessons" && /^\/lessons\/[^/]+$/.test(pathname)),
    );
  return matched?.labelKey ?? "admin:navigation.overview";
}
