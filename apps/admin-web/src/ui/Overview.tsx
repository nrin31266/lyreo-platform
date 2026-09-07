import { useTranslation } from 'react-i18next';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';

export function Overview() {
  const { t } = useTranslation('admin');
  const stats = [
    [t('overview.stats.lessons.label'), '—', t('overview.stats.lessons.note')],
    [t('overview.stats.jobs.label'), '—', t('overview.stats.jobs.note')],
    [t('overview.stats.lexicon.label'), '—', t('overview.stats.lexicon.note')],
    [t('overview.stats.toeic.label'), '2019–2026', t('overview.stats.toeic.note')],
  ] as const;

  return (
    <section>
      <div className="eyebrow">{t('overview.eyebrow')}</div>
      <h1>{t('overview.title')}</h1>
      <p className="lead">{t('overview.lead')}</p>

      <div className="stat-grid">
        {stats.map(([label, value, note]) => (
          <article className="stat" key={label}>
            <small>{label}</small>
            <strong>{value}</strong>
            <span>{note}</span>
          </article>
        ))}
      </div>

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>{t('overview.architecturePulse')}</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="pill-row">
            <span>{t('overview.architecture.springModulith')}</span>
            <span>{t('overview.architecture.fastApiRuntime')}</span>
            <span>{t('overview.architecture.r2Artifacts')}</span>
            <span>{t('overview.architecture.postgresJobs')}</span>
            <span>{t('overview.architecture.noKafka')}</span>
            <span>{t('overview.architecture.noRedis')}</span>
          </div>
        </CardContent>
      </Card>
    </section>
  );
}
