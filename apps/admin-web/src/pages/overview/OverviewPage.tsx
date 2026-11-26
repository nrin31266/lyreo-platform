import { useTranslation } from 'react-i18next';
import { RiLayoutGridLine } from '@remixicon/react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';

export function OverviewPage() {
  const { t } = useTranslation('admin');

  return (
    <div className="flex min-h-[60vh] items-center justify-center p-4">
      <Card className="w-full max-w-lg border-dashed text-center">
        <CardHeader className="items-center gap-3">
          <div className="flex size-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
            <RiLayoutGridLine className="size-6" aria-hidden="true" />
          </div>
          <CardTitle>{t('overviewDashboard.blankTitle')}</CardTitle>
          <CardDescription>{t('overviewDashboard.blankSubtitle')}</CardDescription>
        </CardHeader>
        <CardContent className="text-xs text-muted-foreground">
          {t('overviewDashboard.blankHint')}
        </CardContent>
      </Card>
    </div>
  );
}
