import { Fragment } from 'react';
import { useTranslation } from 'react-i18next';
import { RiArrowLeftSLine, RiArrowRightSLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';

/** Zero-based pages; omit pageCount for cursor-backed lists. */
export function AdminPagination({ page, pageCount, hasNext = false, disabled = false, canNavigate = () => true, onPageChange }: {
  page: number;
  pageCount?: number;
  hasNext?: boolean;
  disabled?: boolean;
  canNavigate?: (page: number) => boolean;
  onPageChange: (page: number) => void;
}) {
  const { t } = useTranslation('admin');
  const last = pageCount === undefined ? page + (hasNext ? 1 : 0) : Math.max(0, pageCount - 1);
  const pages = new Set([0, page]);
  if (pageCount !== undefined) pages.add(last);
  const start = Math.max(0, Math.min(page - 1, last - 2));
  const end = Math.min(last, Math.max(page + 1, 2));
  for (let number = start; number <= end; number++) {
    if (pageCount !== undefined || canNavigate(number)) pages.add(number);
  }
  const visible = [...pages].sort((a, b) => a - b);
  const next = pageCount === undefined ? hasNext : page < last;
  return <nav aria-label={t('pagination.label')} aria-busy={disabled} className="flex max-w-full flex-wrap items-center justify-end gap-1">
    <Button type="button" variant="ghost" disabled={disabled || page === 0 || !canNavigate(page - 1)} aria-label={t('catalog.previous')} onClick={() => onPageChange(page - 1)}>
      <RiArrowLeftSLine aria-hidden="true" /><span className="hidden sm:inline">{t('catalog.previous')}</span>
    </Button>
    {visible.map((number, index) => <Fragment key={number}>
      {index > 0 && number - visible[index - 1] > 1 && <span aria-hidden="true" className="flex size-9 items-center justify-center text-muted-foreground">…</span>}
      <Button type="button" variant={number === page ? 'outline' : 'ghost'} className="min-w-9 tabular-nums" aria-label={t('pagination.page', { number: number + 1 })} aria-current={number === page ? 'page' : undefined} disabled={disabled || !canNavigate(number)} onClick={() => { if (number !== page) onPageChange(number); }}>{number + 1}</Button>
    </Fragment>)}
    <Button type="button" variant="ghost" disabled={disabled || !next || !canNavigate(page + 1)} aria-label={t('catalog.next')} onClick={() => onPageChange(page + 1)}>
      <span className="hidden sm:inline">{t('catalog.next')}</span><RiArrowRightSLine aria-hidden="true" />
    </Button>
  </nav>;
}
