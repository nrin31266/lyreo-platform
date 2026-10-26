import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { playbackAction } from "./authoring-editor";
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
        end: number;
        playsinline: number;
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
  loopRange,
  onPlayingChange,
}: {
  videoId: string;
  startMs: number;
  endMs: number;
  sentences: AuthoringSentence[];
  seek: { atMs: number; sequence: number } | null;
  onTimeChange: (atMs: number) => void;
  onAvailabilityChange?: (available: boolean) => void;
  onPlayingChange?: (playing: boolean) => void;
  transport?: { playing: boolean; rate: number; sequence: number };
  loopRange?: { startMs: number; endMs: number };
}) {
  const { t } = useTranslation("admin");
  const host = useRef<HTMLDivElement>(null);
  const player = useRef<Player | null>(null);
  const pendingSeek = useRef<number | null>(null);
  const latest = useRef({
    startMs,
    endMs,
    sentences,
    onTimeChange,
    onAvailabilityChange,
    onPlayingChange,
    loopRange,
  });
  latest.current = {
    startMs,
    endMs,
    sentences,
    onTimeChange,
    onAvailabilityChange,
    onPlayingChange,
    loopRange,
  };
  const [error, setError] = useState(false);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let disposed = false;
    let timer: ReturnType<typeof setInterval> | undefined;
    pendingSeek.current = null;
    setReady(false);
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
            end: Math.ceil(endMs / 1000),
            playsinline: 1,
          },
          events: {
            onReady() {
              if (disposed) return;
              setReady(true);
              latest.current.onAvailabilityChange?.(true);
              player.current?.seekTo(startMs / 1000, true);
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
                if (pendingSeek.current !== null) {
                  const requested = pendingSeek.current;
                  notify(Math.max(0, requested - start));
                  if (current.getPlayerState() === 1) {
                    current.seekTo(requested / 1000, true);
                    pendingSeek.current = null;
                  }
                  return;
                }
                notify(Math.max(0, sourceMs - start));
                latest.current.onPlayingChange?.(
                  current.getPlayerState() === 1,
                );
                if (current.getPlayerState() !== 1) return;
                const loop = latest.current.loopRange;
                if (
                  loop &&
                  (sourceMs < loop.startMs || sourceMs >= loop.endMs)
                ) {
                  current.seekTo(loop.startMs / 1000, true);
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
      if (timer) clearInterval(timer);
      player.current?.destroy();
      player.current = null;
      host.current?.replaceChildren();
    };
  }, [videoId, startMs, endMs]);

  useEffect(() => {
    if (!ready || !seek || !player.current) return;
    const target = startMs + seek.atMs;
    // An unstarted iframe may defer seeking until its first actual play.
    pendingSeek.current = [-1, 5].includes(player.current.getPlayerState())
      ? target
      : null;
    player.current.seekTo(target / 1000, true);
    latest.current.onTimeChange(Math.max(0, target - startMs));
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

  return (
    <div className="flex flex-col gap-2">
      <div
        ref={host}
        aria-label={t("lessonAuthoring.player")}
        className="aspect-video w-full [&_iframe]:size-full"
      />
      {error ? (
        <Alert variant="destructive">
          <AlertDescription>
            {t("lessonAuthoring.playerError")}
          </AlertDescription>
        </Alert>
      ) : null}
      {!!sentences.length && <p className="text-sm text-muted-foreground">
        {t("lessonAuthoring.playerNote")}
      </p>}
    </div>
  );
}
