import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { createPortal } from "react-dom";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { RiPlayLine as Play, RiPauseLine as Pause, RiRestartLine as RotateCcw, RiSkipBackLine as SkipBack, RiSkipForwardLine as SkipForward, RiRepeat2Line as Repeat2, RiPlayList2Line } from "@remixicon/react";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { playbackAction, sentencePlaybackAction, sentencePlaybackRange } from "./authoring-editor";
import type { AuthoringSentence } from "./lesson-builder.types";

type Player = {
  destroy(): void;
  getCurrentTime(): number;
  getPlayerState(): number;
  pauseVideo(): void;
  playVideo(): void;
  setPlaybackRate(rate: number): void;
  seekTo(seconds: number, allowSeekAhead: boolean): void;
};
type YouTube = {
  Player: new (
    container: HTMLElement,
    options: {
      videoId: string;
      playerVars: {
        origin: string;
        start: number;
        playsinline: number;
        autoplay: number;
      };
      events: { onReady(): void; onError(): void };
    },
  ) => Player;
};
declare global {
  interface Window {
    YT?: YouTube;
    onYouTubeIframeAPIReady?: () => void;
  }
}
let apiPromise: Promise<YouTube> | undefined;
function loadYouTube(): Promise<YouTube> {
  if (window.YT?.Player) return Promise.resolve(window.YT);
  if (!apiPromise) {
    apiPromise = new Promise((resolve, reject) => {
      const previous = window.onYouTubeIframeAPIReady;
      window.onYouTubeIframeAPIReady = () => {
        previous?.();
        if (window.YT) resolve(window.YT);
      };
      const script = document.createElement("script");
      script.src = "https://www.youtube.com/iframe_api";
      script.async = true;
      script.onerror = () => {
        apiPromise = undefined;
        reject(new Error("YouTube unavailable"));
      };
      document.head.append(script);
    });
  }
  return apiPromise;
}

export function YouTubePreview({
  videoId,
  startMs,
  endMs,
  sentences,
  seek,
  onTimeChange,
  onAvailabilityChange,
  transport,
  loopRange, controlsHost,
  onPlayingChange,
  thumbnailUrl,
  review,
}: {
  thumbnailUrl?: string | null;
  videoId: string;
  startMs: number;
  endMs: number;
  sentences: AuthoringSentence[];
  seek: { atMs: number; sequence: number; play?: boolean } | null;
  onTimeChange: (atMs: number) => void;
  onAvailabilityChange?: (available: boolean) => void;
  onPlayingChange?: (playing: boolean) => void;
  transport?: { playing: boolean; rate: number; sequence: number };
  loopRange?: { startMs: number; endMs: number };
  controlsHost?: HTMLElement | null;
  review?: { sentenceId: string | null; context: string; onSentenceChange: (id: string) => void };
}) {
  const { t } = useTranslation("admin");
  const host = useRef<HTMLDivElement>(null);
  const player = useRef<Player | null>(null);
  const pendingSeek = useRef<number | null>(null);
  const appliedSeek = useRef<typeof seek>(null);
  const latest = useRef({
    startMs,
    endMs,
    sentences,
    onTimeChange,
    onAvailabilityChange,
    onPlayingChange,
    loopRange,
    review,
  });
  latest.current = {
    startMs,
    endMs,
    sentences,
    onTimeChange,
    onAvailabilityChange,
    onPlayingChange,
    loopRange,
    review,
  };
  const [error, setError] = useState(false);
  const [retry, setRetry] = useState(0);
  const [isPlaying, setIsPlaying] = useState(false);
  const [hasPlayed, setHasPlayed] = useState(false);
  const [rate, setRate] = useState(1);
  const [localLoop, setLocalLoop] = useState(false);
  const localLoopRef = useRef(false);
  localLoopRef.current = localLoop;
  const [continuous, setContinuous] = useState(false);
  const continuousRef = useRef(false);
  continuousRef.current = continuous;
  const playingSentence = useRef<string | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let disposed = false;
    let timer: ReturnType<typeof setInterval> | undefined;
    const timeout = setTimeout(() => {
      if (!disposed) { setError(true); latest.current.onAvailabilityChange?.(false); }
    }, 15000);
    pendingSeek.current = null;
    setReady(false);
    setHasPlayed(false);
    setIsPlaying(false);
    setContinuous(false);
    setLocalLoop(false);
    latest.current.onAvailabilityChange?.(false);
    setError(false);
    void loadYouTube()
      .then((YT) => {
        if (disposed || !host.current) return;
        const target = document.createElement("div");
        host.current.append(target);
        player.current = new YT.Player(target, {
          videoId,
          playerVars: {
            origin: window.location.origin,
            start: Math.floor(startMs / 1000),
            playsinline: 1,
            autoplay: 0,
          },
          events: {
            onReady() {
              if (disposed) return;
              clearTimeout(timeout);
              setError(false);
              setReady(true);
              latest.current.onAvailabilityChange?.(true);
              if (player.current?.getPlayerState()===1) player.current.pauseVideo();
              timer = setInterval(() => {
                const current = player.current;
                if (!current) return;
                const {
                  startMs: start,
                  endMs: end,
                  sentences: rows,
                  onTimeChange: notify,
                } = latest.current;
                const sourceMs = current.getCurrentTime() * 1000;
                const playing = current.getPlayerState() === 1;
                if (playing) setHasPlayed(true);
                setIsPlaying(playing);
                latest.current.onPlayingChange?.(playing);
                if (pendingSeek.current !== null) {
                  const requested = pendingSeek.current;
                  notify(Math.min(end - start, Math.max(0, requested - start)));
                  if (current.getPlayerState() === 1) {
                    current.seekTo(requested / 1000, true);
                    pendingSeek.current = null;
                  }
                  return;
                }
                notify(Math.min(end - start, Math.max(0, sourceMs - start)));
                if (current.getPlayerState() !== 1) return;
                const review = latest.current.review;
                const selectedId = playingSentence.current ?? review?.sentenceId;
                const selected = rows.find(row => row.id === selectedId);
                const range = selected && sentencePlaybackRange(selected, rows, end-start);
                if(review && !range) { current.pauseVideo();setIsPlaying(false);return; }
                const loop = latest.current.loopRange ?? (localLoopRef.current
                  ? review && range ? {startMs:start+range.startMs,endMs:start+range.endMs} : { startMs: start, endMs: end } : undefined);
                if (
                  loop &&
                  (sourceMs < loop.startMs || sourceMs >= loop.endMs)
                ) {
                  current.seekTo(loop.startMs / 1000, true);
                  return;
                }
                if (review) {
                  const action = sentencePlaybackAction(sourceMs-start, selectedId ?? null, continuousRef.current, rows, end-start);
                  if (action.pause) {
                    current.pauseVideo();setIsPlaying(false);
                    if (action.stopMs !== undefined) { current.seekTo((start+action.stopMs)/1000,true);notify(action.stopMs); }
                  }
                  if (action.sentenceId) { playingSentence.current=action.sentenceId;review.onSentenceChange(action.sentenceId); }
                  if (action.seekMs !== undefined) current.seekTo((start+action.seekMs)/1000,true);
                  return;
                }
                const action = playbackAction(sourceMs, start, end, rows);
                if (action.pause) current.pauseVideo();
                if (action.seekMs !== undefined)
                  current.seekTo(action.seekMs / 1000, true);
              }, 100);
            },
            onError() {
              if (!disposed) {
                setError(true);
                latest.current.onAvailabilityChange?.(false);
              }
            },
          },
        });
      })
      .catch(() => {
        if (!disposed) {
          setError(true);
          latest.current.onAvailabilityChange?.(false);
        }
      });
    return () => {
      disposed = true;
      clearTimeout(timeout);
      if (timer) clearInterval(timer);
      player.current?.destroy();
      player.current = null;
      host.current?.replaceChildren();
    };
  }, [videoId, retry]);

  useEffect(() => {
    const current=player.current;
    if (!ready || !current) return;
    if ([-1,5].includes(current.getPlayerState())) pendingSeek.current=startMs;
    else current.seekTo(startMs/1000,true);
    latest.current.onTimeChange(0);
  }, [startMs]);
  useEffect(() => {
    player.current?.pauseVideo();setIsPlaying(false);pendingSeek.current=null;
    setLocalLoop(false);playingSentence.current=null;
  }, [review?.context]);
  useEffect(() => {
    if (ready && !transport) player.current?.setPlaybackRate(rate);
  }, [rate, ready, transport]);

  useEffect(() => {
    if (!ready || !seek || !player.current || appliedSeek.current===seek) return;
    appliedSeek.current=seek;
    const target = startMs + seek.atMs;
    // An unstarted iframe may defer seeking until its first actual play.
    pendingSeek.current = [-1, 5].includes(player.current.getPlayerState())
      ? target
      : null;
    if (pendingSeek.current === null) player.current.seekTo(target / 1000, true);
    if (latest.current.review) playingSentence.current=latest.current.review.sentenceId;
    if (seek.play === true) player.current.playVideo();
    if (seek.play === false) { player.current.pauseVideo();setIsPlaying(false); }
    latest.current.onTimeChange(Math.min(endMs - startMs, Math.max(0, target - startMs)));
  }, [seek, startMs, ready]);

  useEffect(() => {
    if (!ready || !transport) return;
    player.current?.setPlaybackRate(transport.rate);
    if (transport.playing) {
      player.current?.playVideo();
      if (pendingSeek.current !== null) {
        player.current?.seekTo(pendingSeek.current / 1000, true);
      }
    } else player.current?.pauseVideo();
  }, [transport, ready]);

  function togglePlayback() {
    const current=player.current;
    if (!current) return;
    if (current.getPlayerState()===1) { current.pauseVideo();setIsPlaying(false);return; }
    const selected=sentences.find(row=>row.id===review?.sentenceId);
    const range=selected && sentencePlaybackRange(selected,sentences,endMs-startMs);
    if (review && !range) return;
    if (range) {
      const atMs=current.getCurrentTime()*1000-startMs;
      if (atMs<range.startMs || atMs>=range.endMs) {
        pendingSeek.current=startMs+range.startMs;
        current.seekTo((startMs+range.startMs)/1000,true);
      }
      playingSentence.current=review!.sentenceId;
    }
    current.playVideo();
  }
  function skipBy(deltaMs:number) {
    const selected=sentences.find(row=>row.id===review?.sentenceId);
    const range=selected && sentencePlaybackRange(selected,sentences,endMs-startMs);
    const min=startMs+(range?.startMs??0),max=range?startMs+range.endMs:endMs;
    const target=Math.max(min,Math.min(max, (player.current?.getCurrentTime()??0)*1000+deltaMs));
    if (player.current && [-1,5].includes(player.current.getPlayerState())) pendingSeek.current=target;
    else player.current?.seekTo(target/1000,true);
    latest.current.onTimeChange(target-startMs);
  }

  const controls = <div className="player-transport">
        <Tooltip delayDuration={400}><TooltipTrigger asChild><Button size="icon" variant="outline" disabled={!ready || error || (!!review && !sentences.some(row=>row.id===review.sentenceId))} aria-label={t(isPlaying ? "waveform.pause" : "waveform.play")} onClick={togglePlayback}>
          {isPlaying ? <Pause /> : <Play />}
        </Button></TooltipTrigger><TooltipContent>{t(isPlaying ? "waveform.pause" : "waveform.play")}</TooltipContent></Tooltip>
        <Tooltip delayDuration={400}><TooltipTrigger asChild><Button size="icon" variant="ghost" disabled={!ready || error} aria-label={t("authoringUi.backFive")} onClick={() => skipBy(-5000)}><SkipBack /></Button></TooltipTrigger><TooltipContent>{t("authoringUi.backFive")}</TooltipContent></Tooltip>
        <Tooltip delayDuration={400}><TooltipTrigger asChild><Button size="icon" variant="ghost" disabled={!ready || error} aria-label={t("authoringUi.forwardFive")} onClick={() => skipBy(5000)}><SkipForward /></Button></TooltipTrigger><TooltipContent>{t("authoringUi.forwardFive")}</TooltipContent></Tooltip>
        {!!review && <Tooltip><TooltipTrigger asChild><Button size="icon" variant={continuous ? 'secondary' : 'ghost'} aria-label={t('reviewWorkspace.continuousPlayback')} aria-pressed={continuous} onClick={()=>{setContinuous(!continuous);setLocalLoop(false);}}><RiPlayList2Line /></Button></TooltipTrigger><TooltipContent className="max-w-xs">{t('reviewWorkspace.continuousPlaybackHelp')}</TooltipContent></Tooltip>}
        <Tooltip delayDuration={400}><TooltipTrigger asChild><Button size="icon" variant={localLoop ? "secondary" : "ghost"} disabled={!ready || error} aria-label={t(review ? 'reviewWorkspace.loopSentence' : "waveform.loop")} aria-pressed={localLoop} onClick={() => {setLocalLoop(!localLoop);if(review)setContinuous(false);}}><Repeat2 /></Button></TooltipTrigger><TooltipContent>{t(review ? 'reviewWorkspace.loopSentence' : "waveform.loop")}</TooltipContent></Tooltip>
        {[0.75, 1, 1.25].map(value => <Button key={value} size="sm" variant={rate === value ? "secondary" : "ghost"} disabled={!ready || error} onClick={() => setRate(value)}>{value}×</Button>)}
      </div>;
  return (
    <div className="youtube-preview">
      <div className="youtube-frame" aria-busy={!ready && !error}>
        {(!hasPlayed || !ready || error) && <img className="youtube-poster" src={thumbnailUrl || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`} alt="" />}
        <div ref={host} aria-label={t("lessonAuthoring.player")} className="youtube-host" />
        {!ready && !error && <span role="status" className="youtube-loading">{t("loading")}</span>}
      </div>
      {error && <Alert variant="destructive"><AlertDescription className="flex items-center justify-between gap-2">
        {t("lessonAuthoring.playerError")}
        <Tooltip delayDuration={400}><TooltipTrigger asChild><Button size="icon" variant="outline" aria-label={t("lessonAuthoring.retryInspect")} onClick={() => { apiPromise = undefined; setRetry(n => n + 1); }}><RotateCcw /></Button></TooltipTrigger><TooltipContent>{t("lessonAuthoring.retryInspect")}</TooltipContent></Tooltip>
      </AlertDescription></Alert>}
      {!transport && (controlsHost ? createPortal(controls, controlsHost) : controls)}
    </div>
  );
}
