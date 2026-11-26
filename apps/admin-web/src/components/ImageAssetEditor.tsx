import { lazy, Suspense, useEffect, useId, useState } from 'react';
import type { Area } from 'react-easy-crop';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { RiDeleteBinLine, RiImageAddLine, RiLink, RiUpload2Line } from '@remixicon/react';
import { api } from '@/lib/api';
import { AuthenticatedImage } from '@/components/AuthenticatedImage';
import { FilePicker } from '@/components/FilePicker';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';

const Cropper = lazy(() => import('react-easy-crop'));

const ACCEPT_IMAGES = 'image/png,image/jpeg,image/webp';
const MAX_UPLOAD_BYTES = 4 * 1024 * 1024;

type UploadedAsset = {
  id: string;
  mimeType: string;
  sizeBytes: number;
  filename: string | null;
};

async function cropImageToWebpFile(
  sourceUrl: string,
  pixels: Area,
  outputWidth: number,
  outputHeight: number,
  baseName: string,
): Promise<File> {
  const image = new Image();
  image.src = sourceUrl;
  await image.decode();

  const canvas = document.createElement('canvas');
  canvas.width = outputWidth;
  canvas.height = outputHeight;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Canvas 2D context unavailable');
  ctx.clearRect(0, 0, outputWidth, outputHeight);
  ctx.drawImage(
    image,
    pixels.x,
    pixels.y,
    pixels.width,
    pixels.height,
    0,
    0,
    outputWidth,
    outputHeight,
  );

  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (result) => (result ? resolve(result) : reject(new Error('Could not encode WebP image'))),
      'image/webp',
      0.84,
    );
  });
  const stem = baseName.replace(/\.[^.]+$/, '') || 'asset';
  return new File([blob], `${stem}.webp`, { type: 'image/webp' });
}

export function ImageAssetEditor({
  mode,
  value,
  onChange,
  label,
  alt,
  aspect = 1,
  outputWidth = 192,
  outputHeight = 192,
  disabled = false,
  rounded = false,
  urlInputId,
}: {
  mode: 'asset' | 'url';
  value: string | null | undefined;
  onChange: (next: string | null) => void;
  label: string;
  alt: string;
  aspect?: number;
  outputWidth?: number;
  outputHeight?: number;
  disabled?: boolean;
  rounded?: boolean;
  urlInputId?: string;
}) {
  const { t } = useTranslation('admin');
  const zoomId = useId();
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [previewSrc, setPreviewSrc] = useState<string | null>(null);
  const [crop, setCrop] = useState({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(1);
  const [pixels, setPixels] = useState<Area | null>(null);
  const [clientError, setClientError] = useState<string | null>(null);

  useEffect(() => {
    if (!selectedFile) {
      setPreviewSrc(null);
      return;
    }
    const url = URL.createObjectURL(selectedFile);
    setPreviewSrc(url);
    return () => URL.revokeObjectURL(url);
  }, [selectedFile]);

  const uploadMutation = useMutation({
    mutationFn: async () => {
      if (!previewSrc || !pixels || !selectedFile) {
        throw new Error('Select a crop region before uploading');
      }
      const webpFile = await cropImageToWebpFile(
        previewSrc,
        pixels,
        outputWidth,
        outputHeight,
        selectedFile.name,
      );
      const body = new FormData();
      body.append('file', webpFile);
      return api<UploadedAsset>('/api/v1/admin/content-media', {
        method: 'POST',
        body,
      });
    },
    onSuccess: (asset) => {
      const nextValue =
        mode === 'url'
          ? `/api/v1/admin/content-media/${encodeURIComponent(asset.id)}/file`
          : asset.id;
      onChange(nextValue);
      setSelectedFile(null);
      setPixels(null);
      setZoom(1);
      setCrop({ x: 0, y: 0 });
    },
  });

  function handleSelectFile(file: File) {
    setClientError(null);
    uploadMutation.reset();
    if (!file.size || file.size > MAX_UPLOAD_BYTES) {
      setClientError(t('imageEditor.fileTooLarge'));
      return;
    }
    if (file.type && !file.type.startsWith('image/')) {
      setClientError(t('library.wrongMediaType'));
      return;
    }
    setSelectedFile(file);
    setCrop({ x: 0, y: 0 });
    setZoom(1);
    setPixels(null);
  }

  const trimmed = (value ?? '').trim();
  const displaySrc = trimmed
    ? mode === 'asset'
      ? `/api/v1/admin/content-media/${encodeURIComponent(trimmed)}/file`
      : trimmed
    : null;
  const isAuthenticatedApiSrc = Boolean(displaySrc?.startsWith('/api/'));

  return (
    <div className="grid gap-2">
      <Label htmlFor={urlInputId}>{label}</Label>
      <div className="flex flex-wrap items-center gap-3 rounded-xl border bg-muted/20 p-3">
        {displaySrc ? (
          isAuthenticatedApiSrc ? (
            <AuthenticatedImage
              src={displaySrc}
              alt={alt}
              className={
                aspect === 1
                  ? mode === 'asset'
                    ? `size-20 shrink-0 object-contain drop-shadow-sm ${rounded ? 'rounded-full' : ''}`
                    : `size-16 shrink-0 border bg-background p-1 object-contain ${rounded ? 'rounded-full' : 'rounded-xl'}`
                  : 'h-16 w-28 shrink-0 rounded-lg border bg-background object-cover'
              }
              fallback={
                <div className="flex size-16 shrink-0 items-center justify-center rounded-xl border bg-muted text-muted-foreground">
                  <RiImageAddLine className="size-6" aria-hidden="true" />
                </div>
              }
            />
          ) : (
            <img
              src={displaySrc}
              alt={alt}
              className={
                aspect === 1
                  ? `size-16 shrink-0 border bg-background object-cover ${rounded ? 'rounded-full' : 'rounded-xl'}`
                  : 'h-16 w-28 shrink-0 rounded-lg border bg-background object-cover'
              }
            />
          )
        ) : (
          <div
            className={
              aspect === 1
                ? `flex size-16 shrink-0 items-center justify-center border border-dashed bg-background text-muted-foreground ${rounded ? 'rounded-full' : 'rounded-xl'}`
                : 'flex h-16 w-28 shrink-0 items-center justify-center rounded-lg border border-dashed bg-background text-muted-foreground'
            }
          >
            <RiImageAddLine className="size-6" aria-hidden="true" />
          </div>
        )}

        <div className="min-w-0 flex-1 space-y-2">
          {mode === 'url' ? (
            <div className="relative">
              <RiLink
                className="pointer-events-none absolute top-2.5 left-2.5 size-4 text-muted-foreground"
                aria-hidden="true"
              />
              <Input
                id={urlInputId}
                type="url"
                placeholder="https://..."
                disabled={disabled}
                className="pl-8"
                value={value ?? ''}
                onChange={(event) => onChange(event.target.value)}
              />
            </div>
          ) : null}

          <div className="flex flex-wrap items-center gap-2">
            <FilePicker
              label={t('imageEditor.uploadButton')}
              accept={ACCEPT_IMAGES}
              disabled={disabled || uploadMutation.isPending}
              onSelect={handleSelectFile}
            />
            {trimmed ? (
              <Button
                type="button"
                size="sm"
                variant="ghost"
                disabled={disabled || uploadMutation.isPending}
                onClick={() => onChange(mode === 'url' ? '' : null)}
              >
                <RiDeleteBinLine data-icon="inline-start" aria-hidden="true" />
                {t('imageEditor.clearButton')}
              </Button>
            ) : null}
          </div>
          <p className="text-xs text-muted-foreground">{t('imageEditor.compressHelp')}</p>
        </div>
      </div>

      {clientError ? (
        <p role="alert" className="text-xs text-destructive">
          {clientError}
        </p>
      ) : null}

      <Dialog
        open={Boolean(previewSrc)}
        onOpenChange={(open) => {
          if (!open && !uploadMutation.isPending) {
            setSelectedFile(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-lg" showCloseButton={!uploadMutation.isPending}>
          <DialogHeader>
            <DialogTitle>{t('imageEditor.cropTitle')}</DialogTitle>
            <DialogDescription>
              {t('imageEditor.cropHelp', { width: outputWidth, height: outputHeight })}
            </DialogDescription>
          </DialogHeader>

          {previewSrc ? (
            <div className="space-y-3">
              <div className="relative h-64 w-full overflow-hidden rounded-xl border bg-muted">
                <Suspense fallback={<div className="h-full w-full bg-muted" />}>
                  <Cropper
                    image={previewSrc}
                    crop={crop}
                    zoom={zoom}
                    aspect={aspect}
                    cropShape={rounded ? 'round' : 'rect'}
                    onCropChange={setCrop}
                    onZoomChange={setZoom}
                    onCropComplete={(_, areaPixels) => setPixels(areaPixels)}
                  />
                </Suspense>
              </div>
              <div className="grid gap-1">
                <Label htmlFor={zoomId} className="text-xs text-muted-foreground">
                  {t('imageEditor.zoomLabel')}
                </Label>
                <input
                  id={zoomId}
                  type="range"
                  min={1}
                  max={3}
                  step={0.05}
                  value={zoom}
                  onChange={(event) => setZoom(Number(event.target.value))}
                  className="w-full accent-primary"
                />
              </div>
            </div>
          ) : null}

          <CatalogError error={uploadMutation.error} />

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={uploadMutation.isPending}
              onClick={() => setSelectedFile(null)}
            >
              {t('catalog.cancel')}
            </Button>
            <Button
              type="button"
              disabled={uploadMutation.isPending || !pixels}
              onClick={() => uploadMutation.mutate()}
            >
              <RiUpload2Line data-icon="inline-start" aria-hidden="true" />
              {t(uploadMutation.isPending ? 'imageEditor.uploading' : 'imageEditor.confirmUpload')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
