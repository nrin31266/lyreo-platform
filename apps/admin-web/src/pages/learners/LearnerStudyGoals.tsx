import { useQuery } from '@tanstack/react-query';
import { RiFireLine, RiFocus3Line } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchLearnerGamification, type LearnerEntry } from './learners.api';
import { MetricHelp } from './MetricHelp';

export function LearnerStudyGoals({ learner }: { learner: LearnerEntry }) {
  const { t, i18n } = useTranslation('admin');
  const streak = useQuery({
    queryKey: ['learner-gamification', learner.learnerId],
    queryFn: ({ signal }) => fetchLearnerGamification(learner.learnerId, signal),
  });
  const streakDate = streak.data?.lastActiveDate;
  const dateFormat = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeZone: 'UTC' });

  return (
    <div className="grid gap-2 sm:grid-cols-2">
      <section className="space-y-1 rounded-lg border p-3">
        <h3 className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
          <RiFocus3Line className="size-4" aria-hidden="true" />
          {t('learnerProgress.toeicTarget.title')}
        </h3>
        <p className="text-sm font-semibold tabular-nums">
          {learner.toeicTargetScore == null
            ? t('learnerProgress.toeicTarget.unset')
            : t('learnerProgress.toeicTarget.points', { score: learner.toeicTargetScore })}
        </p>
      </section>
      <section className="space-y-0.5 rounded-lg border p-3">
        <h3 className="flex items-center gap-1 text-xs font-medium text-muted-foreground">
          <RiFireLine className="size-4" aria-hidden="true" />
          {t('learnerProgress.streakSummary.title')}
          <MetricHelp label={t('learnerProgress.streakSummary.title')} calculation="streak" />
        </h3>
        {streak.data ? (
          <>
            <p className="text-sm font-semibold tabular-nums">
              {t('learnerProgress.streakSummary.value', {
                current: streak.data.currentStreak,
                longest: streak.data.longestStreak,
              })}
            </p>
            {streakDate && <p className="text-xs text-muted-foreground">{t('learnerProgress.streakSummary.lastActive', { date: dateFormat.format(new Date(streakDate)) })}</p>}
          </>
        ) : streak.error ? (
          <CatalogError error={streak.error} retry={() => void streak.refetch()} />
        ) : (
          <p className="text-sm text-muted-foreground">{t('loading')}</p>
        )}
      </section>
    </div>
  );
}
