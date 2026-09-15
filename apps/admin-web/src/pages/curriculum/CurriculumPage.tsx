import { useTranslation } from 'react-i18next';
import { Card, CardContent } from '@/components/ui/card';

export function CurriculumPage() {
  const { t } = useTranslation('admin');
  return (
    <section>
      <div className="eyebrow">{t('placeholders.eyebrow')}</div>
      <h1>{t('placeholders.curriculum.title')}</h1>
      <Card className="mt-6">
        <CardContent className="pt-6">
          <p>{t('placeholders.curriculum.text')}</p>
          <p className="muted">{t('placeholders.boundary')}</p>
        </CardContent>
      </Card>
    </section>
  );
}
