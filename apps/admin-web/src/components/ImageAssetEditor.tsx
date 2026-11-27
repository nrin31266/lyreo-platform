import { lazy, Suspense, useEffect, useId, useRef, useState } from 'react';
import type { Area } from 'react-easy-crop';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  RiCheckLine,
  RiDeleteBinLine,
  RiImageAddLine,
  RiLink,
  RiLoader4Line,
  RiUpload2Line,
} from '@remixicon/react';
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
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CONTENT_MEDIA_RE = /\/api\/v1\/admin\/content-media\/([0-9a-f-]{36})\/file/i;

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
      0.86,
    );
  });
  const stem = baseName.replace(/\.[^.]+$/, '') || 'asset';
  return new File([blob], `${stem}.webp`, { type: 'image/webp' });
}

async function centerCropUrlToWebpFile(
  remoteUrl: string,
  outputWidth: number,
  outputHeight: number,
): Promise<File> {
  const response = await fetch(remoteUrl);
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  const sourceBlob = await response.blob();
  const objectUrl = URL.createObjectURL(sourceBlob);
  try {
    const image = new Image();
    image.src = objectUrl;
    await image.decode();

    const targetRatio = outputWidth / outputHeight;
    const srcW = image.naturalWidth || outputWidth;
    const srcH = image.naturalHeight || outputHeight;
    const srcRatio = srcW / srcH;

    let sx = 0;
    let sy = 0;
    let sw = srcW;
    let sh = srcH;
    if (srcRatio > targetRatio) {
      sw = Math.round(srcH * targetRatio);
      sx = Math.round((srcW - sw) / 2);
    } else if (srcRatio < targetRatio) {
      sh = Math.round(srcW / targetRatio);
      sy = Math.round((srcH - sh) / 2);
    }

    const canvas = document.createElement('canvas');
    canvas.width = outputWidth;
    canvas.height = outputHeight;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('Canvas 2D context unavailable');
    ctx.clearRect(0, 0, outputWidth, outputHeight);
    ctx.drawImage(image, sx, sy, sw, sh, 0, 0, outputWidth, outputHeight);

    const webpBlob = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob(
        (result) => (result ? resolve(result) : reject(new Error('Could not encode WebP image'))),
        'image/webp',
        0.88,
      );
    });
    return new File([webpBlob], 'cover-url.webp', { type: 'image/webp' });
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
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
  compact = false,
  displayOverrideSrc,
  urlPlaceholder,
  urlApplyLabel,
  urlErrorLabel,
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
  compact?: boolean;
  displayOverrideSrc?: string | null;
  urlPlaceholder?: string;
  urlApplyLabel?: string;
  urlErrorLabel?: string;
}) {
  const { t } = useTranslation('admin');
  const zoomId = useId();
  const compactUrlId = useId();
  const compactFileRef = useRef<HTMLInputElement>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [previewSrc, setPreviewSrc] = useState<string | null>(null);
  const [crop, setCrop] = useState({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(1);
  const [pixels, setPixels] = useState<Area | null>(null);
  const [clientError, setClientError] = useState<string | null>(null);
  const [urlInput, setUrlInput] = useState('');

  useEffect(() => {
    setUrlInput('');
    setClientError(null);
  }, [value]);

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

  const urlImportMutation = useMutation({
    mutationFn: async (rawUrl: string) => {
      const webpFile = await centerCropUrlToWebpFile(rawUrl, outputWidth, outputHeight);
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
      setUrlInput('');
      setClientError(null);
    },
    onError: () => {
      setClientError(urlErrorLabel ?? t('library.wrongMediaType'));
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

  function applyUrlInput() {
    const candidate = urlInput.trim();
    if (!candidate) return;
    setClientError(null);
    if (mode === 'url') {
      onChange(candidate);
      setUrlInput('');
      return;
    }
    if (UUID_RE.test(candidate)) {
      onChange(candidate);
      setUrlInput('');
      return;
    }
    const mediaMatch = CONTENT_MEDIA_RE.exec(candidate);
    if (mediaMatch) {
      onChange(mediaMatch[1]);
      setUrlInput('');
      return;
    }
    if (!/^(https?:\/\/|\/)/i.test(candidate)) {
      setClientError(urlErrorLabel ?? t('library.wrongMediaType'));
      return;
    }
    urlImportMutation.mutate(candidate);
  }

  const trimmed = (value ?? '').trim();
  const displaySrc =
    displayOverrideSrc ??
    (trimmed
      ? mode === 'asset'
        ? `/api/v1/admin/content-media/${encodeURIComponent(trimmed)}/file`
        : trimmed
      : null);
  const isAuthenticatedApiSrc = Boolean(displaySrc?.startsWith('/api/'));
  const busy = disabled || uploadMutation.isPending || urlImportMutation.isPending;

  const cropDialog = (
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
  );

  if (compact) {
    const resolvedInputId = urlInputId ?? compactUrlId;
    return (
      <div className="grid gap-1.5">
        <Label htmlFor={resolvedInputId}>{label}</Label>
        <div className="flex items-center gap-2.5 rounded-xl border bg-muted/20 px-2.5 py-1.5">
          {displaySrc ? (
            isAuthenticatedApiSrc ? (
              <AuthenticatedImage
                src={displaySrc}
                alt={alt}
                className="h-10 w-16 shrink-0 rounded-xl border border-border/70 bg-background object-cover shadow-2xs"
                fallback={
                  <div className="flex h-10 w-16 shrink-0 items-center justify-center rounded-xl border border-border/70 bg-muted text-muted-foreground">
                    <RiImageAddLine className="size-4" aria-hidden="true" />
                  </div>
                }
              />
            ) : (
              <img
                src={displaySrc}
                alt={alt}
                className="h-10 w-16 shrink-0 rounded-xl border border-border/70 bg-background object-cover shadow-2xs"
              />
            )
          ) : (
            <div className="flex h-10 w-16 shrink-0 items-center justify-center rounded-xl border border-dashed bg-background text-muted-foreground">
              <RiImageAddLine className="size-4" aria-hidden="true" />
            </div>
          )}

          <input
            ref={compactFileRef}
            type="file"
            accept={ACCEPT_IMAGES}
            disabled={busy}
            className="sr-only"
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = '';
              if (file) handleSelectFile(file);
            }}
          />
          <Button
            type="button"
            size="icon-sm"
            variant="outline"
            disabled={busy}
            tooltip={t('imageEditor.uploadButton')}
            aria-label={t('imageEditor.uploadButton')}
            onClick={() => compactFileRef.current?.click()}
          >
            <RiUpload2Line aria-hidden="true" />
          </Button>

          <div className="relative min-w-[11rem] flex-1">
            <RiLink
              className="pointer-events-none absolute top-2.5 left-2.5 size-4 text-muted-foreground"
              aria-hidden="true"
            />
            <Input
              id={resolvedInputId}
              type="url"
              disabled={busy}
              placeholder={urlPlaceholder ?? 'https://...'}
              className="h-9 pl-8 text-sm"
              value={mode === 'url' ? (value ?? '') : urlInput}
              onChange={(event) => {
                setClientError(null);
                if (mode === 'url') {
                  onChange(event.target.value);
                } else {
                  setUrlInput(event.target.value);
                }
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && mode === 'asset' && urlInput.trim()) {
                  event.preventDefault();
                  applyUrlInput();
                }
              }}
            />
          </div>

          {mode === 'asset' && urlInput.trim() ? (
            <Button
              type="button"
              size="icon-sm"
              variant="secondary"
              disabled={busy}
              tooltip={urlApplyLabel ?? 'OK'}
              aria-label={urlApplyLabel ?? 'OK'}
              onClick={applyUrlInput}
            >
              {urlImportMutation.isPending ? (
                <RiLoader4Line className="animate-spin" aria-hidden="true" />
              ) : (
                <RiCheckLine aria-hidden="true" />
              )}
            </Button>
          ) : null}

          {trimmed ? (
            <Button
              type="button"
              size="icon-sm"
              variant="ghost"
              aria-label={t('imageEditor.clearButton')}
              tooltip={t('imageEditor.clearButton')}
              disabled={busy}
              onClick={() => onChange(mode === 'url' ? '' : null)}
            >
              <RiDeleteBinLine aria-hidden="true" />
            </Button>
          ) : null}
        </div>

        {clientError ? (
          <p role="alert" className="text-xs text-destructive">
            {clientError}
          </p>
        ) : null}

        {cropDialog}
      </div>
    );
  }

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

      {cropDialog}
    </div>
  );
}
