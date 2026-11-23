import { useMutation, useQueryClient } from '@tanstack/react-query';
import { RiPencilLine } from '@remixicon/react';
import { lazy, Suspense, useEffect, useId, useRef, useState } from 'react';
import type { Area } from 'react-easy-crop';
import { useTranslation } from 'react-i18next';
import { FilePicker } from '@/components/FilePicker';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { LearnerAvatar, learnerAvatarColor, learnerInitials } from './LearnerAvatar';
import { avatarPackIds, avatarPortraitSource, portraitPacks } from './learner-avatar-presets';
import { updateLearnerAppearance, uploadLearnerAvatar, type AvatarPreset, type LearnerEntry } from './learners.api';

const Cropper = lazy(() => import('react-easy-crop'));
const imageTypes = ['image/jpeg', 'image/png'];
const maxSourceBytes = 2 * 1024 * 1024;
const optionClass = 'h-24 w-full min-w-0 flex-col gap-2 overflow-hidden rounded-lg border-0 px-1.5 py-2 aria-pressed:bg-primary/10 aria-pressed:text-primary';

type AppearanceChange =
  | { displayName: string }
  | { avatarPreset: AvatarPreset | null }
  | { source: string; pixels: Area };

function useAppearanceMutation(entry: LearnerEntry, onSaved: () => void) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (change: AppearanceChange) => 'source' in change
      ? uploadLearnerAvatar(entry.learnerId, await croppedAvatar(change.source, change.pixels))
      : updateLearnerAppearance(entry.learnerId, change),
    onSuccess: updated => {
      queryClient.setQueryData(['learner-directory', entry.learnerId], updated);
      void queryClient.invalidateQueries({ queryKey: ['learner-directory'] });
      onSaved();
    },
  });
}

async function croppedAvatar(source: string, pixels: Area) {
  const image = new Image();
  image.src = source;
  await image.decode();

  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 256;
  const drawing = canvas.getContext('2d');
  if (!drawing) throw new Error('Avatar crop unavailable');
  drawing.drawImage(image, pixels.x, pixels.y, pixels.width, pixels.height, 0, 0, 256, 256);

  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(value => value ? resolve(value) : reject(new Error('Avatar crop could not be saved')), 'image/png');
  });
  return new File([blob], 'avatar.png', { type: 'image/png' });
}

export function LearnerNameEditor({ entry }: { entry: LearnerEntry }) {
  const { t } = useTranslation(['admin', 'common']);
  const [name, setName] = useState<string | null>(null);
  const editButton = useRef<HTMLButtonElement>(null);
  const nameInput = useRef<HTMLInputElement>(null);
  const title = entry.displayName || entry.email || t('navigation.profiles');

  function close() {
    setName(null);
    requestAnimationFrame(() => editButton.current?.focus());
  }

  const mutation = useAppearanceMutation(entry, close);
  useEffect(() => {
    nameInput.current?.focus();
  }, [name !== null]);

  if (name === null) {
    return (
      <div className="flex min-w-0 items-center gap-1">
        <h1 className="min-w-0 truncate" title={title}>{title}</h1>
        <Button ref={editButton} variant="ghost" size="icon-sm" type="button"
          aria-label={t('learnerProfiles.editName')} onClick={() => {
            mutation.reset();
            setName(entry.displayName ?? '');
          }}>
          <RiPencilLine aria-hidden="true" />
        </Button>
      </div>
    );
  }

  const canSave = !mutation.isPending && name.trim() !== (entry.displayName ?? '');
  return (
    <form className="grid min-w-0 gap-2" onSubmit={event => {
      event.preventDefault();
      if (canSave) mutation.mutate({ displayName: name });
    }} onKeyDown={event => {
      if (event.key === 'Escape' && !mutation.isPending) close();
    }}>
      <h1 className="sr-only">{title}</h1>
      <div className="flex flex-wrap items-center gap-2">
        <Input ref={nameInput} aria-label={t('learnerProfiles.name')} maxLength={80}
          className="w-full sm:w-60" value={name} onChange={event => setName(event.target.value)} />
        <Button type="submit" size="sm" disabled={!canSave}>{t('learnerProfiles.saveName')}</Button>
        <Button type="button" size="sm" variant="ghost" disabled={mutation.isPending} onClick={close}>
          {t('common:actions.cancel')}
        </Button>
      </div>
      <CatalogError error={mutation.error} />
    </form>
  );
}

export function LearnerAvatarDialog({ entry }: { entry: LearnerEntry }) {
  const { t } = useTranslation(['admin', 'common']);
  const [open, setOpen] = useState(false);
  const [selection, setSelection] = useState<AvatarPreset | null | undefined>(undefined);
  const [photo, setPhoto] = useState<File | null>(null);
  const [source, setSource] = useState<string>();
  const [photoError, setPhotoError] = useState<'avatarUploadHelp' | 'avatarLoadError'>();
  const [crop, setCrop] = useState({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(1);
  const [pixels, setPixels] = useState<Area>();
  const zoomId = useId();
  const packHeadingId = useId();

  useEffect(() => {
    if (!photo) return;
    const url = URL.createObjectURL(photo);
    setSource(url);
    return () => URL.revokeObjectURL(url);
  }, [photo]);

  function resetPhoto() {
    setPhoto(null);
    setSource(undefined);
    setPhotoError(undefined);
    setPixels(undefined);
    setCrop({ x: 0, y: 0 });
    setZoom(1);
  }

  function close() {
    setOpen(false);
    setSelection(undefined);
    resetPhoto();
  }

  const mutation = useAppearanceMutation(entry, close);
  const chosen = selection === undefined ? entry.avatarPreset : selection;
  const hasSelectionChange = selection !== undefined
    && (selection !== entry.avatarPreset || Boolean(entry.avatarUploadId));

  return (
    <Dialog open={open} onOpenChange={next => {
      if (mutation.isPending) return;
      if (next) {
        mutation.reset();
        setOpen(true);
      } else close();
    }}>
      <DialogTrigger asChild>
        <Button type="button" variant="ghost" size="icon-lg" className="relative rounded-full"
          aria-label={t('learnerProfiles.changeAvatar')}>
          <LearnerAvatar entry={entry} size="lg" />
          <span className="absolute -right-1 -bottom-1 grid size-5 place-items-center rounded-full bg-background">
            <RiPencilLine className="size-3!" aria-hidden="true" />
          </span>
        </Button>
      </DialogTrigger>
      <DialogContent className="flex max-h-[calc(100dvh-2rem)] flex-col gap-4 overflow-hidden sm:max-w-lg"
        showCloseButton={!mutation.isPending}>
        <DialogHeader>
          <DialogTitle>{t(photo ? 'learnerProfiles.cropAvatar' : 'learnerProfiles.changeAvatar')}</DialogTitle>
          <DialogDescription>{t(photo ? 'learnerProfiles.cropHelp' : 'learnerProfiles.chooseAvatarHelp')}</DialogDescription>
        </DialogHeader>
        {photo ? (
          <div className="grid min-h-0 gap-3 overflow-y-auto">
            <div className="relative h-60 overflow-hidden rounded-lg bg-muted sm:h-72">
              {source && (
                <Suspense fallback={<p role="status" className="p-3">{t('loading')}</p>}>
                  <Cropper image={source} crop={crop} zoom={zoom} aspect={1} cropShape="round" showGrid={false}
                    onCropChange={setCrop} onZoomChange={setZoom} onCropComplete={(_, area) => setPixels(area)}
                    cropperProps={{ 'aria-label': t('learnerProfiles.cropAvatar') }}
                    mediaProps={{ alt: t('learnerProfiles.avatar'), onError: () => setPhotoError('avatarLoadError') }}
                  />
                </Suspense>
              )}
            </div>
            <div className="flex items-center gap-3">
              <label htmlFor={zoomId}>{t('learnerProfiles.avatarZoom')}</label>
              <input id={zoomId} type="range" min={1} max={3} step={0.05} value={zoom} className="min-w-0 flex-1 accent-primary"
                disabled={mutation.isPending} onChange={event => setZoom(event.target.valueAsNumber)} />
            </div>
          </div>
        ) : (
          <>
            <div role="region" aria-label={t('learnerProfiles.avatarPacks')} tabIndex={0}
              className="grid max-h-[min(55dvh,30rem)] min-h-0 gap-4 overflow-y-auto overscroll-contain p-1">
              {avatarPackIds.map(pack => (
                <section key={pack} aria-labelledby={`${packHeadingId}-${pack}`} className="grid gap-1">
                  <h3 id={`${packHeadingId}-${pack}`} className="px-1.5 text-sm font-semibold">
                    {t(`learnerProfiles.packs.${pack}`)}
                  </h3>
                  <div className="grid grid-cols-3 gap-1 sm:grid-cols-4">
                    {pack === 'animals' && (
                      <Button type="button" variant="ghost" className={optionClass}
                        title={t('learnerProfiles.initials')}
                        aria-pressed={(!chosen || !avatarPortraitSource(chosen)) && (selection !== undefined || !entry.avatarUploadId)}
                        disabled={mutation.isPending}
                        onClick={() => { mutation.reset(); setSelection(null); }}>
                        <span aria-hidden="true" className="learner-avatar-initials flex size-12 items-center justify-center rounded-full"
                          data-color={learnerAvatarColor(entry.learnerId)}>
                          {learnerInitials(entry.displayName, entry.email)}
                        </span>
                        <span className="w-full truncate">{t('learnerProfiles.initials')}</span>
                      </Button>
                    )}
                    {portraitPacks[pack].map(preset => {
                      const label = t(`learnerProfiles.presets.${preset}`);
                      return (
                        <Button key={preset} type="button" variant="ghost" aria-pressed={chosen === preset}
                          className={optionClass} title={label} disabled={mutation.isPending}
                          onClick={() => { mutation.reset(); setSelection(preset); }}>
                          <img src={avatarPortraitSource(preset)} alt="" loading="lazy" className="size-12 rounded-full object-cover" />
                          <span className="w-full truncate">{label}</span>
                        </Button>
                      );
                    })}
                  </div>
                </section>
              ))}
              <div className="px-1.5 text-xs text-muted-foreground">
                <a href="https://www.dicebear.com/styles/toon-head/" target="_blank" rel="noreferrer"
                  className="underline underline-offset-2">Toon Head · Johan Melin</a>
                {' · '}
                <a href="https://creativecommons.org/licenses/by/4.0/" target="_blank" rel="noreferrer"
                  className="underline underline-offset-2">CC BY 4.0</a>
              </div>
            </div>
            <FilePicker label={t('learnerProfiles.choosePhoto')} accept={imageTypes.join(',')} disabled={mutation.isPending}
              onSelect={file => {
                mutation.reset();
                resetPhoto();
                if (!imageTypes.includes(file.type) || file.size > maxSourceBytes || file.size === 0) {
                  setPhotoError('avatarUploadHelp');
                  return;
                }
                setPhoto(file);
              }} />
          </>
        )}
        {photoError && <p role="alert" className="text-sm text-destructive">{t(`learnerProfiles.${photoError}`)}</p>}
        <CatalogError error={mutation.error} />
        <DialogFooter>
          <Button type="button" variant="outline" disabled={mutation.isPending}
            onClick={photo ? () => { mutation.reset(); resetPhoto(); } : close}>
            {t(photo ? 'learnerProfiles.backToAvatars' : 'common:actions.cancel')}
          </Button>
          <Button type="button" disabled={mutation.isPending || (photo ? !source || !pixels || !!photoError : !hasSelectionChange)}
            onClick={() => {
              if (photo && source && pixels) mutation.mutate({ source, pixels });
              else if (selection !== undefined) mutation.mutate({ avatarPreset: selection });
            }}>
            {t('learnerProfiles.saveAvatar')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
