import { useTranslation } from 'react-i18next';
import { Progress } from '@/components/ui/progress';
import type { LearnerSkillSummary } from '@/pages/overview/admin-ops.api';
import { MetricHelp } from './MetricHelp';

const skills = ['LISTENING', 'SPEAKING', 'VOCABULARY', 'GRAMMAR', 'TOEIC_LISTENING', 'TOEIC_READING'];

export function LearnerSkillScores({ scores }: { scores: LearnerSkillSummary[] }) {
  const { t, i18n } = useTranslation('admin');
  const bySkill = new Map(scores.map((score) => [score.skillCode, score]));
  const percent = new Intl.NumberFormat(i18n.resolvedLanguage, { style: 'percent', maximumFractionDigits: 1 });
  return (
    <section className="space-y-2">
      <h3 className="flex items-center gap-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {t('learnerProgress.skillsTitle')}
        <MetricHelp label={t('learnerProgress.skillsTitle')} calculation="skills" />
      </h3>
      <ul className="grid gap-x-4 gap-y-2 sm:grid-cols-2">
        {skills.map((code) => {
          const skill = bySkill.get(code);
          const label = t(`learnerProgress.skills.${code}`);
          return (
            <li key={code} className="space-y-1">
              <div className="flex flex-wrap items-baseline justify-between gap-x-1.5 text-xs">
                <span className="flex items-center gap-0.5 font-medium">
                  {label}
                  {code === 'LISTENING' && <MetricHelp label={label} calculation="dictation" />}
                  {code === 'SPEAKING' && <MetricHelp label={label} calculation="shadowingActivity" />}
                </span>
                {skill && skill.sampleCount > 0 ? (
                  <span className="tabular-nums text-muted-foreground">
                    {percent.format(Number(skill.score) / 100)} · {t('learnerProgress.skillSamples', { count: skill.sampleCount })}
                  </span>
                ) : <span className="text-muted-foreground">{t('learnerProgress.skillUnmeasured')}</span>}
              </div>
              {skill && skill.sampleCount > 0
                ? <Progress aria-label={label} value={Math.min(100, Math.max(0, Number(skill.score)))} />
                : <div className="h-2 rounded-full bg-muted" aria-hidden="true" />}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
