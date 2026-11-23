import { RiPauseLine, RiVolumeUpLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { ipaWordUrl, type IpaExample } from './ipa';
import { ipaPlaybackKey, type IpaPlayback, type useIpaAudio } from './useIpaAudio';

export function IpaAudioButtons({ example, audio, beforePlay }: {
  example: IpaExample;
  audio: ReturnType<typeof useIpaAudio>;
  beforePlay?: () => void;
}) {
  const { t } = useTranslation('admin');
  const { ipa, word } = example;

  function listen(playback: IpaPlayback, label: string) {
    const active = audio.playing === ipaPlaybackKey(ipa, playback);
    const Icon = active ? RiPauseLine : RiVolumeUpLine;
    return <Button type="button" size="sm" variant={playback === 'sequence' ? 'secondary' : 'outline'} aria-pressed={active}
      onClick={() => { beforePlay?.(); audio.toggle(example, playback); }}>
      <Icon aria-hidden="true" />{label}
    </Button>;
  }

  const hasWordAudio = Boolean(ipaWordUrl(example));
  if (example.lessonUrl && !example.soundUrl && !example.soundFile) return <Button asChild size="sm" variant="outline" className="justify-self-start">
    <a href={example.lessonUrl} target="_blank" rel="noreferrer" onClick={beforePlay}>
      <RiVolumeUpLine aria-hidden="true" />{t('phonetics.watchBbc')}
    </a>
  </Button>;

  return <>
    <div className="flex flex-wrap gap-2">
      {hasWordAudio && listen('sequence', t('phonetics.playSequence'))}
      {listen('sound', t('phonetics.playSound', { sound: ipa }))}
      {hasWordAudio && listen('word', t('phonetics.playExample', { word, transcription: example.transcription }))}
    </div>
    {audio.failed && <p role="alert" className="text-sm text-destructive">{t('phonetics.playError')}</p>}
  </>;
}
