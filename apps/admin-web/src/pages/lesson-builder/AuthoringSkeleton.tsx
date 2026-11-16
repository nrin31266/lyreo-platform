import { Skeleton } from '@/components/ui/skeleton';
import { useTranslation } from 'react-i18next';

export function SourceInspectionSkeleton({ metadata = false }: { metadata?: boolean }) {
  const { t } = useTranslation('admin');
  return <div className={metadata ? 'source-details-skeleton' : 'source-range-editor source-range-skeleton'} role="status" aria-label={t('twoStep.inspecting')}>
    {metadata ? <><Skeleton className="h-4 w-20" /><Skeleton className="h-9 w-full" /><Skeleton className="h-3 w-4/5" /><Skeleton className="h-4 w-24" />{[0, 1, 2].map(i => <Skeleton key={i} className="h-5 w-2/3" />)}<Skeleton className="h-9 w-full" />{[0, 1, 2].map(i => <div className="source-settings-skeleton" key={i}><Skeleton className="h-5 w-1/2" /><Skeleton className="h-8 w-full" /><Skeleton className="h-8 w-full" /></div>)}</>
      : <><Skeleton className="source-player w-full" /><div className="source-timeline source-timeline-skeleton"><Skeleton className="h-8 w-2/3" /><Skeleton className="h-24 w-full" /><Skeleton className="h-9 w-3/4" /><Skeleton className="h-3 w-full" /></div></>}
  </div>;
}
export function TranscriptSkeleton() {
  const { t } = useTranslation('admin');
  return <div role="status" aria-label={t('finish.loadingTranscript')}>{[0, 1, 2].map(i => <div className="studio-sentence" key={i}><Skeleton className="h-3 w-28" /><Skeleton className="mt-5 h-5 w-5/6" /><Skeleton className="mt-3 h-4 w-2/3" /><Skeleton className="mt-4 h-5 w-1/2" /></div>)}</div>;
}
export function LessonEditorSkeleton() {
  const { t } = useTranslation('admin');
  return <section className="lesson-studio" aria-busy="true" aria-label={t('loading')}><Skeleton className="h-10 w-1/2" /><Skeleton className="h-32 w-full" /><div className="studio-workspace"><Skeleton className="aspect-video w-full" /><TranscriptSkeleton /></div></section>;
}
