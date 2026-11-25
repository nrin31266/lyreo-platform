import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { RiCheckLine, RiEyeLine, RiFileCopyLine, RiImage2Line, RiSearchLine, RiVolumeUpLine } from '@remixicon/react';
import { AdminPagination } from '@/components/AdminPagination';
import { FilePicker } from '@/components/FilePicker';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { MediaPreview } from '@/pages/library/ContentMedia';
import {
  fetchContentMediaList,
  uploadContentMedia,
  type ContentMediaItem,
} from '@/pages/overview/admin-ops.api';

function formatBytes(sizeBytes: number) {
  if (sizeBytes < 1024) return `${sizeBytes} B`;
  return `${(sizeBytes / 1024).toFixed(1)} KB`;
}

export function MediaLibraryPage() {
  const { t } = useTranslation('admin');
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [queryText, setQueryText] = useState('');
  const [kind, setKind] = useState('all');
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [previewItem, setPreviewItem] = useState<ContentMediaItem | null>(null);

  const mediaQuery = useQuery({
    queryKey: ['admin-content-media', page, queryText, kind],
    queryFn: ({ signal }) =>
      fetchContentMediaList(
        { page, size: 12, query: queryText.trim(), kind },
        signal,
      ),
  });

  const uploadMutation = useMutation({
    mutationFn: (file: File) => uploadContentMedia(file),
    onSuccess: async () => {
      setPage(0);
      await queryClient.invalidateQueries({ queryKey: ['admin-content-media'] });
    },
  });

  const total = mediaQuery.data?.total ?? 0;
  const pageSize = mediaQuery.data?.size || 12;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));

  async function copyAssetId(id: string) {
    try {
      await navigator.clipboard?.writeText(id);
    } catch {
      // Clipboard API may be unavailable in headless tests
    }
    setCopiedId(id);
    setTimeout(() => setCopiedId((current) => (current === id ? null : current)), 1500);
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{t('mediaLibrary.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('mediaLibrary.subtitle')}</p>
        </div>
        <FilePicker
          label={uploadMutation.isPending ? t('mediaLibrary.uploading') : t('mediaLibrary.upload')}
          accept="image/jpeg,image/png,image/webp,audio/mpeg,audio/wav,audio/mp4"
          fileName={uploadMutation.variables?.name}
          disabled={uploadMutation.isPending}
          onSelect={(file) => uploadMutation.mutate(file)}
        />
      </div>

      {uploadMutation.error ? <CatalogError error={uploadMutation.error} /> : null}
      {mediaQuery.error ? (
        <CatalogError error={mediaQuery.error} retry={() => void mediaQuery.refetch()} />
      ) : null}

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <RiSearchLine
            className="pointer-events-none absolute top-2.5 left-3 size-4 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            value={queryText}
            onChange={(event) => {
              setQueryText(event.target.value);
              setPage(0);
            }}
            placeholder={t('mediaLibrary.searchPlaceholder')}
            className="pl-9"
          />
        </div>
        <Select
          value={kind}
          onValueChange={(value) => {
            setKind(value);
            setPage(0);
          }}
        >
          <SelectTrigger className="w-44">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              <SelectItem value="all">{t('mediaLibrary.kinds.all')}</SelectItem>
              <SelectItem value="image">{t('mediaLibrary.kinds.image')}</SelectItem>
              <SelectItem value="audio">{t('mediaLibrary.kinds.audio')}</SelectItem>
            </SelectGroup>
          </SelectContent>
        </Select>
      </div>

      {mediaQuery.isPending ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <Skeleton className="h-36 w-full" />
          <Skeleton className="h-36 w-full" />
          <Skeleton className="h-36 w-full" />
        </div>
      ) : !mediaQuery.data || mediaQuery.data.items.length === 0 ? (
        <Empty className="border py-10">
          <EmptyHeader>
            <RiImage2Line className="size-8 text-muted-foreground" aria-hidden="true" />
            <EmptyTitle>{t('mediaLibrary.emptyTitle')}</EmptyTitle>
            <EmptyDescription>{t('mediaLibrary.emptyHelp')}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {mediaQuery.data.items.map((item) => {
              const isAudio = item.mimeType.startsWith('audio/');
              return (
                <Card key={item.id}>
                  <CardHeader className="pb-2">
                    <div className="flex items-start justify-between gap-2">
                      <CardTitle className="truncate text-sm">
                        {item.filename || item.id}
                      </CardTitle>
                      <Badge variant="outline" className="shrink-0">
                        {isAudio ? (
                          <RiVolumeUpLine className="mr-1 size-3" aria-hidden="true" />
                        ) : (
                          <RiImage2Line className="mr-1 size-3" aria-hidden="true" />
                        )}
                        {item.mimeType}
                      </Badge>
                    </div>
                    <CardDescription className="truncate font-mono text-xs">{item.id}</CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-3">
                    <div className="flex items-center justify-between text-xs text-muted-foreground tabular-nums">
                      <span>{formatBytes(item.sizeBytes)}</span>
                      <span>
                        {item.synthetic ? t('mediaLibrary.synthesized') : item.createdBy || 'upload'}
                      </span>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        onClick={() => setPreviewItem(item)}
                      >
                        <RiEyeLine aria-hidden="true" />
                        {t('mediaLibrary.preview')}
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        onClick={() => void copyAssetId(item.id)}
                      >
                        {copiedId === item.id ? (
                          <RiCheckLine aria-hidden="true" />
                        ) : (
                          <RiFileCopyLine aria-hidden="true" />
                        )}
                        {copiedId === item.id ? t('mediaLibrary.copied') : t('mediaLibrary.copyId')}
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              );
            })}
          </div>

          <AdminPagination
            page={page}
            pageCount={pageCount}
            onPageChange={setPage}
          />
        </>
      )}

      <Dialog open={Boolean(previewItem)} onOpenChange={(open) => !open && setPreviewItem(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{previewItem?.filename || previewItem?.id}</DialogTitle>
            <DialogDescription className="font-mono text-xs">
              {previewItem?.id} · {previewItem?.mimeType}
            </DialogDescription>
          </DialogHeader>
          {previewItem ? (
            <MediaPreview
              assetId={previewItem.id}
              role={previewItem.mimeType.startsWith('audio/') ? 'audio' : 'image'}
            />
          ) : null}
        </DialogContent>
      </Dialog>
    </div>
  );
}
