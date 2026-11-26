import {
  RiAwardLine as Award,
  RiBookOpenLine as BookOpen,
  RiBookShelfLine as Library,
  RiDashboardLine as LayoutDashboard,
  RiEqualizerLine as Settings2,
  RiFlagLine as Target,
  RiGroupLine as Users,
  RiImage2Line as Images,
  RiLineChartLine as ChartNoAxesCombined,
  RiListCheck3 as ListChecks,
  RiMicLine as Mic,
  RiNotification3Line as Bell,
  RiStackLine as Layers,
  RiVipCrownLine as Crown,
} from "@remixicon/react";
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
          { key: "organization", path: "/lessons/organization" },
        ],
      },
      {
        key: "languageLibrary",
        icon: Library,
        children: [
          { key: "lexicon", path: "/lexicon" },
          { key: "vocabulary", path: "/vocabulary" },
          { key: "phonetics", path: "/phonetics" },
          { key: "grammar", path: "/grammar" },
          { key: "toeic", path: "/toeic" },
        ],
      },
      { key: "conversations", icon: Mic, path: "/conversations" },
      { key: "curriculum", icon: Layers, path: "/curriculum" },
    ],
  },
  {
    key: "engagement",
    items: [
      { key: "plans", icon: Crown, path: "/plans" },
      { key: "missions", icon: Target, path: "/missions" },
      { key: "achievements", icon: Award, path: "/achievements" },
      { key: "notifications", icon: Bell, path: "/notifications" },
    ],
  },
  {
    key: "learners",
    items: [
      { key: "profiles", icon: Users, path: "/learners" },
      { key: "progress", icon: ChartNoAxesCombined, path: "/learners/progress" },
    ],
  },
  {
    key: "operations",
    items: [
      { key: "jobs", icon: ListChecks, path: "/jobs" },
      { key: "media", icon: Images, path: "/media" },
    ],
  },
  {
    key: "configuration",
    items: [{ key: "aiSettings", icon: Settings2, path: "/settings/ai" }],
  },
];

export const adminNavItems = adminNavGroups
  .flatMap((g) => g.items.flatMap((i) => i.children ?? [i]))
  .filter((i) => i.path)
  .map((i) => ({ path: i.path!, labelKey: `admin:navigation.${i.key}` }));

export function getNavTitleKey(pathname: string, hash = ""): string {
  const exact = adminNavItems.find((item) => item.path === pathname + hash);
  if (exact) return exact.labelKey;
  if (["/lessons/categories", "/lessons/channels"].includes(pathname)) return "admin:navigation.organization";
  if (/^\/lessons\/[^/]+$/.test(pathname)) return "admin:authoringFlow.editorTitle";
  if (/^\/learners\/[^/]+$/.test(pathname)) return "admin:navigation.profiles";
  for (const library of ["lexicon", "grammar", "toeic"]) {
    if (pathname.startsWith(`/${library}/`)) return `admin:navigation.${library}`;
  }
  const matched = [...adminNavItems]
    .sort((a, b) => b.path.length - a.path.length)
    .find(
      (i) =>
        pathname === i.path.split("#")[0] ||
        (i.path === "/lessons" && /^\/lessons\/[^/]+$/.test(pathname)),
    );
  return matched?.labelKey ?? "admin:navigation.overview";
}
