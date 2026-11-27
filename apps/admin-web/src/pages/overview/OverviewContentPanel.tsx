import {
  RiBookOpenLine,
  RiBookShelfLine,
  RiFileList3Line,
  RiGraduationCapLine,
  RiSearchEyeLine,
  RiStackLine,
} from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import type { AdminDashboardSummary, AnalyticsWeaknessHotspot } from './admin-ops.api';

function formatWeaknessTitle(
  item: AnalyticsWeaknessHotspot,
  t: (key: string, options?: Record<string, unknown>) => string,
  language: string,
) {
  if (item.weaknessType === 'TOEIC_PART') {
    return t('overviewDashboard.weaknessTypes.TOEIC_PART', { key: item.weaknessKey });
  }
  const typeLabel = t(`overviewDashboard.weaknessTypes.${item.weaknessType}`, {
    defaultValue: item.weaknessType,
  });
  const resolved = language.startsWith('en') ? item.labelEn || item.label : item.label || item.labelEn;
  if (resolved) {
    return `${typeLabel} · ${resolved}`;
  }
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-/i.test(item.weaknessKey)) {
    return typeLabel;
  }
  const suffix = t(`overviewDashboard.weaknessKeys.${item.weaknessKey}`, {
    defaultValue: t(`learnersConsole.skills.${item.weaknessKey}`, {
      defaultValue: item.weaknessKey,
    }),
  });
  return `${typeLabel} · ${suffix}`;
}

export function OverviewContentPanel({ data }: { data: AdminDashboardSummary }) {
  const { t, i18n } = useTranslation('admin');
  const fmt = new Intl.NumberFormat(i18n.language);
  const { contentInventory: inv, topCurriculumPaths, topWeaknesses } = data;

  const inventoryItems = [
    {
      to: '/lessons',
      icon: RiBookOpenLine,
      label: t('overviewDashboard.contentSection.lessons'),
      value: fmt.format(inv.lessonsPublished + inv.lessonsDraft),
      sub: t('overviewDashboard.contentSection.lessonsSub', {
        published: fmt.format(inv.lessonsPublished),
        draft: fmt.format(inv.lessonsDraft),
      }),
    },
    {
      to: '/curriculum',
      icon: RiStackLine,
      label: t('overviewDashboard.contentSection.paths'),
      value: fmt.format(inv.curriculumPathsPublished + inv.curriculumPathsDraft),
      sub: t('overviewDashboard.contentSection.pathsSub', {
        published: fmt.format(inv.curriculumPathsPublished),
        enrolled: fmt.format(inv.curriculumEnrollmentsActive),
      }),
    },
    {
      to: '/vocabulary',
      icon: RiBookShelfLine,
      label: t('overviewDashboard.contentSection.vocabulary'),
      value: fmt.format(inv.vocabularyPacksPublished),
      sub: t('overviewDashboard.contentSection.vocabularySub', {
        packs: fmt.format(inv.vocabularyPacksPublished),
        units: fmt.format(inv.vocabularyUnitsTotal),
      }),
    },
    {
      to: '/grammar',
      icon: RiGraduationCapLine,
      label: t('overviewDashboard.contentSection.grammar'),
      value: fmt.format(inv.grammarTopicsPublished),
      sub: t('overviewDashboard.contentSection.grammarSub', {
        topics: fmt.format(inv.grammarTopicsPublished),
      }),
    },
    {
      to: '/toeic',
      icon: RiFileList3Line,
      label: t('overviewDashboard.contentSection.toeic'),
      value: fmt.format(inv.toeicTestsPublished),
      sub: t('overviewDashboard.contentSection.toeicSub', {
        tests: fmt.format(inv.toeicTestsPublished),
      }),
    },
    {
      to: '/lexicon',
      icon: RiSearchEyeLine,
      label: t('overviewDashboard.contentSection.lexicon'),
      value: fmt.format(inv.lexiconHeadwordsTotal),
      sub: t('overviewDashboard.contentSection.lexiconSub', {
        words: fmt.format(inv.lexiconHeadwordsTotal),
      }),
    },
  ];

  return (
    <div className="grid gap-4 lg:grid-cols-12">
      <Card className="lg:col-span-6">
        <CardHeader>
          <CardTitle>{t('overviewDashboard.contentSection.title')}</CardTitle>
          <CardDescription>{t('overviewDashboard.contentSection.help')}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="grid gap-2.5 sm:grid-cols-2 lg:grid-cols-3">
            {inventoryItems.map((item) => {
              const Icon = item.icon;
              return (
                <Link
                  key={item.to}
                  to={item.to}
                  className="rounded-lg border px-3 py-2.5 transition-colors hover:bg-muted/50"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-xs font-medium text-muted-foreground">{item.label}</span>
                    <Icon className="size-3.5 text-muted-foreground" aria-hidden="true" />
                  </div>
                  <p className="mt-1 text-xl font-semibold text-foreground tabular-nums">
                    {item.value}
                  </p>
                  <p className="mt-0.5 truncate text-xs text-muted-foreground">{item.sub}</p>
                </Link>
              );
            })}
          </div>

          <div className="space-y-3 border-t pt-4">
            <h3 className="text-sm font-medium text-foreground">
              {t('overviewDashboard.contentSection.topPathsTitle')}
            </h3>
            {topCurriculumPaths.length === 0 ? (
              <p className="text-xs text-muted-foreground">
                {t('overviewDashboard.contentSection.noPaths')}
              </p>
            ) : (
              <div className="space-y-3">
                {topCurriculumPaths.map((path) => (
                  <Link
                    key={path.pathId}
                    to="/curriculum"
                    className="block rounded-md px-2 py-1.5 transition-colors hover:bg-muted/45"
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="flex min-w-0 items-center gap-2">
                        <span className="truncate text-sm font-medium text-foreground">
                          {path.name}
                        </span>
                        {path.level && (
                          <Badge variant="outline" className="shrink-0 text-[11px]">
                            {path.level}
                          </Badge>
                        )}
                      </div>
                      <span className="shrink-0 text-xs font-medium text-foreground tabular-nums">
                        {path.averageCompletionPercent}%
                      </span>
                    </div>
                    <div className="mt-1.5 flex items-center gap-3">
                      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-primary/15">
                        <div
                          className="h-full rounded-full bg-primary"
                          style={{
                            width: `${Math.max(0, Math.min(100, path.averageCompletionPercent))}%`,
                          }}
                        />
                      </div>
                      <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
                        {t('overviewDashboard.contentSection.enrolledMeta', {
                          enrolled: fmt.format(path.enrolledLearners),
                          completed: fmt.format(path.completedLearners),
                        })}
                      </span>
                    </div>
                  </Link>
                ))}
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      <Card className="lg:col-span-6">
        <CardHeader>
          <CardTitle>{t('overviewDashboard.weaknessesTitle')}</CardTitle>
          <CardDescription>{t('overviewDashboard.weaknessesHelp')}</CardDescription>
        </CardHeader>
        <CardContent>
          {topWeaknesses.length === 0 ? (
            <div className="flex h-52 items-center justify-center rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
              {t('overviewDashboard.noWeaknesses')}
            </div>
          ) : (
            <div className="space-y-3.5">
              {topWeaknesses.map((w) => {
                const severity = Math.max(0, Math.min(100, Math.round(Number(w.averageSeverity))));
                const fillColor =
                  severity >= 70
                    ? 'var(--destructive)'
                    : severity >= 45
                      ? 'var(--warning)'
                      : 'var(--primary)';
                return (
                  <div key={`${w.weaknessType}:${w.weaknessKey}`} className="space-y-1.5">
                    <div className="flex items-baseline justify-between gap-3">
                      <span className="truncate text-sm font-medium text-foreground">
                        {formatWeaknessTitle(w, t, i18n.language)}
                      </span>
                      <div className="flex shrink-0 items-center gap-2.5 text-xs">
                        <span className="text-muted-foreground tabular-nums">
                          {t('overviewDashboard.learnersAffected', {
                            count: fmt.format(w.affectedLearners),
                            samples: fmt.format(w.totalSamples),
                          })}
                        </span>
                        <span className="w-9 text-right font-semibold text-foreground tabular-nums">
                          {severity}%
                        </span>
                      </div>
                    </div>
                    <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                      <div
                        className="h-full rounded-full"
                        style={{
                          width: `${severity}%`,
                          backgroundColor: fillColor,
                        }}
                      />
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
