import { RiQuestionLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

export function MetricHelp({ label, calculation }: { label: string; calculation: string }) {
  const { t } = useTranslation('admin');
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          aria-label={t('learnerProgress.metricHelpLabel', { label })}
          className="inline-grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
          <RiQuestionLine className="size-4" aria-hidden="true" />
        </button>
      </TooltipTrigger>
      <TooltipContent sideOffset={4}>{t(`learnerProgress.calculations.${calculation}`)}</TooltipContent>
    </Tooltip>
  );
}
