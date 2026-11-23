import { useEffect, useRef, useState } from 'react';
import { ipaAudioCredits } from './ipa';

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

  function toggle(id: string, word?: string) {
    const key = ipaPlaybackKey(id, word);
    stop();
    if (playing === key || !ipaAudioCredits[id] || (word && !ipaAudioCredits[word])) return;
    const player = new Audio();
    audio.current = player;
    setPlaying(key);
    function finish(error = false) {
      if (audio.current !== player) return;
      stop();
      setFailed(error);
    }
    function play(next: string) {
      player.src = `${import.meta.env.BASE_URL}ipa/${next}.ogg`;
      void player.play().catch(() => finish(true));
    }
    player.onended = () => {
      if (audio.current !== player) return;
      if (!word) { finish(); return; }
      player.onended = () => finish();
      play(word);
    };
    player.onerror = () => finish(true);
    play(id);
  }

  return { playing, failed, stop, toggle };
}

export const ipaPlaybackKey = (id: string, word?: string) => word ? `${id}/${word}` : id;
