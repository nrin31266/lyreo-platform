import { BookOpen, ListChecks, Plus, Settings2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';

export function OverviewPage() {
  const { t } = useTranslation('admin');
  const areas = [
    { path: '/lessons/new', key: 'authoring', Icon: BookOpen },
    { path: '/jobs', key: 'jobs', Icon: ListChecks },
    { path: '/settings/ai', key: 'providers', Icon: Settings2 },
  ] as const;
  return (
    <section>
      <div className="page-heading">
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
      <div className="console-overview">
        <section className="console-panel" aria-labelledby="workspace-areas">
          <div className="console-panel-heading">
            <h2 id="workspace-areas">{t('overview.areasTitle')}</h2>
          </div>
          {areas.map(({ path, key, Icon }) => (
            <Link to={path} className="console-row" key={key}>
              <Icon aria-hidden="true" />
              <div>
                <strong>{t(`overview.links.${key}.title`)}</strong>
                <p>{t(`overview.links.${key}.description`)}</p>
              </div>
              <span className="console-row-action">{t('overview.open')}</span>
            </Link>
          ))}
        </section>
        <section
          className="console-panel"
          aria-labelledby="authoring-flow-title"
        >
          <div className="console-panel-heading">
            <h2 id="authoring-flow-title">{t('overview.workflowTitle')}</h2>
          </div>
          <ol className="authoring-path">
            {(['source', 'review', 'publish'] as const).map((step) => (
              <li key={step}>
                <h3>{t(`overview.steps.${step}.title`)}</h3>
                <p>{t(`overview.steps.${step}.description`)}</p>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </section>
  );
}
