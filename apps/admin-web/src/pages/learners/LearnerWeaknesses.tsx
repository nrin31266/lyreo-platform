import { weaknessMessageKey } from '@lyreo/i18n';
import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import type { LearnerWeaknessSummary } from '@/pages/overview/admin-ops.api';

export function LearnerWeaknesses({ items }: { items: LearnerWeaknessSummary[] }) {
  const { t, i18n } = useTranslation(['admin', 'common']);
  if (!items.length) return <p className="text-sm text-muted-foreground">{t('common:weakness.empty')}</p>;

  const locale = i18n.resolvedLanguage;
  const percent = new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 1 });
  return <div className="grid gap-2">
    <p className="text-xs text-muted-foreground">{t('common:weakness.help')}</p>
    <ul className="divide-y rounded-lg border">
      {items.map((item) => {
        const localized = locale?.startsWith('vi') ? item.label : item.labelEn ?? item.label;
        const title = localized && localized !== item.weaknessKey
          ? localized
          : t(`common:${weaknessMessageKey(item.weaknessType, item.weaknessKey)}`, { part: item.weaknessKey });
        return <li key={`${item.weaknessType}:${item.weaknessKey}`} className="flex items-center justify-between gap-3 px-3 py-2">
          <span className="min-w-0 text-sm">{title}</span>
          <div className="flex shrink-0 items-center gap-2 text-xs tabular-nums text-muted-foreground">
            {t('common:weakness.samples', { count: item.sampleCount })}
            <Badge variant={Number(item.severity) >= 50 ? 'destructive' : 'warning'}>{percent.format(Number(item.severity) / 100)}</Badge>
          </div>
        </li>;
      })}
    </ul>
  </div>;
}
