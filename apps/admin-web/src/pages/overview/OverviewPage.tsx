import { ArrowUpRight, ListChecks, Plus, Settings2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';

export function OverviewPage() {
  const { t } = useTranslation('admin');
  return (
    <section>
      <div className="overview-intro">
        <div>
          <h1>{t('overview.title')}</h1>
          <p className="lead">{t('overview.lead')}</p>
        </div>
        <Button asChild>
          <Link to="/lessons/new">
            <Plus aria-hidden="true" className="size-4" />
            {t('overview.createLesson')}
          </Link>
        </Button>
      </div>
      <section className="mt-8" aria-labelledby="authoring-flow-title">
        <h2 id="authoring-flow-title" className="text-lg font-semibold">
          {t('overview.workflowTitle')}
        </h2>
        <ol className="authoring-path">
          {(['source', 'review', 'publish'] as const).map((step) => (
            <li key={step}>
              <h3>{t(`overview.steps.${step}.title`)}</h3>
              <p>{t(`overview.steps.${step}.description`)}</p>
            </li>
          ))}
        </ol>
      </section>
      <div className="workspace-links">
        {[
          { path: '/jobs', key: 'jobs', Icon: ListChecks },
          { path: '/settings/ai', key: 'providers', Icon: Settings2 },
        ].map(({ path, key, Icon }) => (
          <Link key={key} to={path} className="workspace-link">
            <Icon aria-hidden="true" />
            <h2>{t(`overview.links.${key}.title`)}</h2>
            <p>{t(`overview.links.${key}.description`)}</p>
            <ArrowUpRight aria-hidden="true" className="workspace-link-arrow" />
          </Link>
        ))}
      </div>
    </section>
  );
}
