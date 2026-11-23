import { useEffect, useRef, useState } from 'react';
import { ipaSoundUrl, ipaWordUrl, type IpaExample } from './ipa';

export type IpaPlayback = 'sequence' | 'sound' | 'word';
export const ipaPlaybackKey = (ipa: string, playback: IpaPlayback) => `${ipa}/${playback}`;

export function useIpaAudio() {
  const audio = useRef<HTMLAudioElement>(null);
  const [playing, setPlaying] = useState<string>();
  const [failed, setFailed] = useState(false);

  useEffect(() => () => { audio.current?.pause(); audio.current = null; }, []);

  function stop() {
    const previous = audio.current;
    audio.current = null;
    previous?.pause();
    setPlaying(undefined);
    setFailed(false);
  }

  function toggle(example: IpaExample, playback: IpaPlayback) {
    const key = ipaPlaybackKey(example.ipa, playback);
    stop();
    if (playing === key) return;
    const word = ipaWordUrl(example);
    const first = playback === 'word' ? word : ipaSoundUrl(example);
    if (!first || (playback === 'sequence' && !word)) return;
    const player = new Audio();
    audio.current = player;
    setPlaying(key);
    function finish(error = false) {
      if (audio.current !== player) return;
      stop();
      setFailed(error);
    }
    function play(next: string) {
      player.src = next;
      void player.play().catch(() => finish(true));
    }
    player.onended = () => {
      if (audio.current !== player) return;
      if (playback !== 'sequence') { finish(); return; }
      player.onended = () => finish();
      play(word);
    };
    player.onerror = () => finish(true);
    play(first);
  }

  return { playing, failed, stop, toggle };
}
