import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { RiCloseLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { AdminPagination } from '@/components/AdminPagination';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchLearners } from '@/pages/learners/learners.api';
import { useDebouncedInput } from '@/hooks/useDebouncedInput';

export const recipientLimit = 100;
export type NotificationRecipient = { id: string; label: string };

export function NotificationRecipientPicker({ selected, onChange, readOnly }: {
  selected: NotificationRecipient[];
  onChange: (selected: NotificationRecipient[]) => void;
  readOnly: boolean;
}) {
  const { t } = useTranslation('admin');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [search, setSearch] = useDebouncedInput(query, value => {
    setQuery(value);
    setPage(0);
  });
  const learners = useQuery({
    queryKey: ['notification-recipient-search', page, query],
    queryFn: ({ signal }) => fetchLearners(page, query.trim(), '', signal),
    enabled: !readOnly,
  });
  const selectedIds = new Set(selected.map(learner => learner.id));
  return (
    <div className="space-y-2">
      {selected.length > 0 && (
        <ul className="flex max-h-24 flex-wrap gap-1.5 overflow-y-auto">
          {selected.map(learner => (
            <li key={learner.id} className="flex max-w-full items-center gap-1 rounded-md bg-muted px-2 py-1 text-xs">
              <span className="wrap-anywhere">{learner.label}</span>
              {!readOnly && <Button type="button" variant="ghost" size="icon-xs"
                aria-label={t('notificationsCenter.removeRecipient', { name: learner.label })}
                onClick={() => onChange(selected.filter(item => item.id !== learner.id))}>
                <RiCloseLine aria-hidden="true" />
              </Button>}
            </li>
          ))}
        </ul>
      )}
      {!readOnly && <>
        <Label htmlFor="notification-recipient-search">{t('notificationsCenter.recipientSearch')}</Label>
        <Input id="notification-recipient-search" maxLength={120} value={search} onChange={event => setSearch(event.target.value)} />
        <CatalogError error={learners.error} retry={() => void learners.refetch()} />
        {learners.isPending ? <Skeleton className="h-24 w-full" /> : (
          <ul className="max-h-40 divide-y overflow-y-auto rounded-lg border">
            {learners.data?.items.map(learner => {
              const checked = selectedIds.has(learner.learnerId);
              const label = learner.displayName || learner.email || learner.learnerId;
              return <li key={learner.learnerId}>
                <Label className="flex cursor-pointer gap-2 px-3 py-2">
                  <Checkbox aria-label={learner.displayName && learner.email ? `${label} (${learner.email})` : label}
                    checked={checked} disabled={!checked && selected.length >= recipientLimit}
                    onCheckedChange={value => onChange(value === true
                      ? [...selected, { id: learner.learnerId, label }]
                      : selected.filter(item => item.id !== learner.learnerId))} />
                  <span className="min-w-0 wrap-anywhere">{label}
                    {learner.displayName && learner.email && <span className="ml-2 text-xs text-muted-foreground">{learner.email}</span>}
                  </span>
                </Label>
              </li>;
            })}
            {learners.data?.items.length === 0 &&
              <li className="p-3 text-sm text-muted-foreground">{t('notificationsCenter.noRecipientsFound')}</li>}
          </ul>
        )}
        {learners.data && learners.data.total > learners.data.size && <AdminPagination page={page}
          pageCount={Math.ceil(learners.data.total / learners.data.size)} onPageChange={setPage} />}
        <p className="text-xs text-muted-foreground">{t('notificationsCenter.selectedLimit', { count: selected.length, limit: recipientLimit })}</p>
      </>}
    </div>
  );
}
