import { RiPauseLine, RiVolumeUpLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { ipaSoundId, type IpaExample } from './ipa';
import { ipaPlaybackKey, type useIpaAudio } from './useIpaAudio';

export function IpaAudioButtons({ example, audio, beforePlay }: {
  example: IpaExample;
  audio: ReturnType<typeof useIpaAudio>;
  beforePlay?: () => void;
}) {
  const { t } = useTranslation('admin');
  const { ipa, word } = example;
  const demo = ipaSoundId(ipa);

  function listen(id: string, label: string, next?: string) {
    const active = audio.playing === ipaPlaybackKey(id, next);
    const Icon = active ? RiPauseLine : RiVolumeUpLine;
    return <Button type="button" size="sm" variant={next ? 'secondary' : 'outline'} aria-pressed={active}
      onClick={() => { beforePlay?.(); audio.toggle(id, next); }}>
      <Icon aria-hidden="true" />{label}
    </Button>;
  }

  return <>
    <div className="flex flex-wrap gap-2">
      {listen(demo, t('phonetics.playSequence'), word)}
      {listen(demo, t('phonetics.playSound', { sound: ipa }))}
      {listen(word, t('phonetics.playExample', { word }))}
    </div>
    {audio.failed && <p role="alert" className="text-sm text-destructive">{t('phonetics.playError')}</p>}
  </>;
}
