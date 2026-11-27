import { weaknessMessageKey } from '@lyreo/i18n';
import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import type { LearnerWeaknessSummary } from '@/pages/overview/admin-ops.api';

function isToeic(item: LearnerWeaknessSummary) {
  return item.weaknessType === 'TOEIC_PART' || item.weaknessType === 'TOEIC_SECTION';
}

export function LearnerWeaknesses({ items }: { items: LearnerWeaknessSummary[] }) {
  const { t, i18n } = useTranslation(['admin', 'common']);
  if (!items.length) return <p className="text-sm text-muted-foreground">{t('common:weakness.empty')}</p>;

  const locale = i18n.resolvedLanguage;
  const percent = new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 1 });
  const groups = [
    [t('learnerProgress.weaknessGroups.toeic'), items.filter(isToeic)],
    [t('learnerProgress.weaknessGroups.other'), items.filter((item) => !isToeic(item))],
  ] as const;
  return (
    <div className="grid gap-2">
      {groups.filter(([, entries]) => entries.length).map(([group, entries]) => (
        <section key={group} className="overflow-hidden rounded-lg border">
          <h5 className="border-b bg-muted/25 px-3 py-1.5 text-xs font-medium">{group}</h5>
          <ul className="divide-y">
            {entries.map((item) => {
              const localized = locale?.startsWith('vi') ? item.label : item.labelEn ?? item.label;
              const title = localized && localized !== item.weaknessKey
                ? localized
                : t(`common:${weaknessMessageKey(item.weaknessType, item.weaknessKey)}`, { part: item.weaknessKey });
              const category = item.weaknessType === 'GRAMMAR_TOPIC'
                ? t('learnerProgress.weaknessGroups.grammar')
                : item.weaknessType === 'VOCABULARY_UNIT' || item.weaknessType === 'VOCABULARY'
                  ? t('learnerProgress.weaknessGroups.vocabulary')
                  : '';
              const detail = item.weaknessType === 'TOEIC_PART'
                ? t(`learnerProgress.toeicParts.${item.weaknessKey}`, { defaultValue: '' })
                : item.weaknessType === 'TOEIC_SECTION'
                  ? t('learnerProgress.weaknessGroups.sectionOnly')
                  : '';
              return (
                <li key={`${item.weaknessType}:${item.weaknessKey}`} className="flex items-center justify-between gap-2 px-3 py-1.5">
                  <span className="min-w-0 text-xs font-medium">
                    {category && localized && <span className="font-normal text-muted-foreground">{category} · </span>}
                    {title}
                    {detail && <span className="font-normal text-muted-foreground"> · {detail}</span>}
                  </span>
                  <div className="flex shrink-0 items-center gap-1.5 text-xs tabular-nums text-muted-foreground">
                    {t('common:weakness.samples', { count: item.sampleCount })}
                    <Badge variant={Number(item.severity) >= 50 ? 'destructive' : 'warning'}>{percent.format(Number(item.severity) / 100)}</Badge>
                  </div>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}
