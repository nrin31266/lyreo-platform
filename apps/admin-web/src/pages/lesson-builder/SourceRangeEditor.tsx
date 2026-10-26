import { createPortal } from "react-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import WaveSurfer from "wavesurfer.js";
import Regions from "wavesurfer.js/dist/plugins/regions.esm.js";
import {
  Play,
  Pause,
  Repeat2,
  ZoomIn,
  ZoomOut,
  AudioLines,
  LoaderCircle,
  Check,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Badge } from "@/components/ui/badge";
import { api } from "@/lib/api";
import { YouTubePreview } from "./YouTubePreview";
import { parseTimecode, timecode } from "./timecode";

type Preview = {
  id: string;
  jobId: string;
  status: string;
  step: string | null;
  error: string | null;
  startMs: number;
  endMs: number;
  peaks: number[];
  audioDurationMs: number | null;
};
export function SourceRangeEditor({
  videoId,
  reference,
  durationMs,
  startMs,
  endMs,
  actionsContainer,
  onRangeChange,
  onValidityChange,
}: {
  videoId: string;
  reference: string;
  durationMs: number;
  startMs: number;
  endMs: number;
  actionsContainer?: HTMLElement | null;
  onRangeChange(start: number, end: number): void;
  onValidityChange?(valid: boolean): void;
}) {
  const { t } = useTranslation("admin");
  const [start, setStart] = useState(timecode(startMs));
  const [end, setEnd] = useState(timecode(endMs));
  const [preview, setPreview] = useState<Preview>();
  const [prepared, setPrepared] = useState<Preview[]>([]);
  const [previewId, setPreviewId] = useState<string>();
  const client = useQueryClient();
  const [error, setError] = useState("");
  const [clock, setClock] = useState(0);
  const [loop, setLoop] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [ready, setReady] = useState(false);
  const [seek, setSeek] = useState<{ atMs: number; sequence: number } | null>(
    null,
  );
  const [transport, setTransport] = useState<{
    playing: boolean;
    rate: number;
    sequence: number;
  }>({ playing: false, rate: 1, sequence: 0 });
  const [rate, setRate] = useState(1);
  const [zoom, setZoom] = useState(0);
  const host = useRef<HTMLDivElement>(null);
  const wave = useRef<WaveSurfer>(null);
  const regions = useRef<ReturnType<typeof Regions.create> | null>(null);
  const syncing = useRef(false);
  const latest = useRef({ startMs, endMs, onRangeChange });
  latest.current = { startMs, endMs, onRangeChange };
  useEffect(() => {
    setStart(timecode(startMs));
    setEnd(timecode(endMs));
  }, [startMs, endMs]);
  const valid =
    startMs >= 0 &&
    endMs > startMs &&
    endMs <= durationMs &&
    endMs - startMs <= 600000;
  const parsedStart = parseTimecode(start),
    parsedEnd = parseTimecode(end);
  const inputValid =
    parsedStart !== null &&
    parsedEnd !== null &&
    parsedStart >= 0 &&
    parsedEnd > parsedStart &&
    parsedEnd <= durationMs &&
    parsedEnd - parsedStart <= 600000;
  useEffect(() => {
    onValidityChange?.(inputValid);
  }, [inputValid, onValidityChange]);
  const matches =
    preview && preview.startMs <= startMs && preview.endMs >= endMs;
  useEffect(() => {
    if (!host.current || !preview?.peaks.length || !preview.audioDurationMs)
      return;
    let disposed = false;
    const plugin = Regions.create();
    regions.current = plugin;
    const colors = getComputedStyle(document.documentElement);
    const ws = WaveSurfer.create({
      container: host.current,
      height: 128,
      waveColor: colors.getPropertyValue("--muted-foreground").trim(),
      progressColor: colors.getPropertyValue("--primary").trim(),
      cursorColor: colors.getPropertyValue("--primary").trim(),
      cursorWidth: 2,
      normalize: false,
      plugins: [plugin],
      interact: true,
      dragToSeek: false,
    });
    wave.current = ws;
    void ws
      .load("", [preview.peaks], preview.audioDurationMs / 1000)
      .then(() => {
        if (disposed) return;
        const current = latest.current;
        if (
          current.startMs >= preview.startMs &&
          current.endMs <= preview.endMs
        )
          plugin.addRegion({
            id: "lesson",
            start: (current.startMs - preview.startMs) / 1000,
            end: (current.endMs - preview.startMs) / 1000,
            color: "color-mix(in oklch, var(--primary) 14%, transparent)",
            drag: true,
            resize: true,
            minLength: 0.001,
            maxLength: 600,
          });
      })
      .catch((cause) => {
        if (!disposed && cause?.name !== "AbortError")
          setError(t("waveform.loadError"));
      });
    ws.on("interaction", (seconds) =>
      setSeek({ atMs: preview.startMs + seconds * 1000, sequence: Date.now() }),
    );
    plugin.on("region-clicked", (region, event) => {
      const box = region.element?.getBoundingClientRect();
      if (!box || box.width === 0) return;
      const fraction = Math.max(
        0,
        Math.min(1, (event.clientX - box.left) / box.width),
      );
      setSeek({
        atMs:
          preview.startMs +
          (region.start + fraction * (region.end - region.start)) * 1000,
        sequence: Date.now(),
      });
    });
    plugin.on("region-updated", (region) => {
      if (!syncing.current)
        latest.current.onRangeChange(
          Math.round(preview.startMs + region.start * 1000),
          Math.round(preview.startMs + region.end * 1000),
        );
    });
    return () => {
      disposed = true;
      ws.destroy();
      wave.current = null;
      regions.current = null;
    };
  }, [preview, t]);
  useEffect(() => {
    const region = regions.current?.getRegions()[0];
    if (!region || !preview || !matches) return;
    syncing.current = true;
    region.setOptions({
      start: (startMs - preview.startMs) / 1000,
      end: (endMs - preview.startMs) / 1000,
    });
    syncing.current = false;
  }, [startMs, endMs, preview, matches]);
  useEffect(() => {
    if (preview?.audioDurationMs && wave.current)
      wave.current.setTime(
        Math.max(
          0,
          Math.min(
            preview.audioDurationMs / 1000,
            (clock - preview.startMs) / 1000,
          ),
        ),
      );
  }, [clock, preview]);
  const preparation = useMutation({
    mutationFn: () =>
      api<Preview>("/api/v1/admin/lesson-source-previews", {
        method: "POST",
        body: JSON.stringify({
          sourceReference: reference,
          startMs,
          endMs,
        }),
      }),
    onSuccess: (result) => {
      client.setQueryData(["source-preview", result.id], result);
      setPreviewId(result.id);
      setPreview(result);
    },
  });
  const previewQuery = useQuery({
    queryKey: ["source-preview", previewId],
    enabled: !!previewId,
    queryFn: ({ signal }) =>
      api<Preview>(`/api/v1/admin/lesson-source-previews/${previewId}`, {
        signal,
      }),
    refetchInterval: (q) =>
      q.state.data &&
      ["QUEUED", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED"].includes(
        q.state.data.status,
      )
        ? 1500
        : false,
  });
  const loading =
    preparation.isPending ||
    (!!previewQuery.data &&
      ["QUEUED", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED"].includes(
        previewQuery.data.status,
      ));
  useEffect(() => {
    const result = previewQuery.data;
    if (!result) return;
    setPreview(result);
    if (result.status === "SUCCEEDED") {
      if (!result.audioDurationMs || !Number.isFinite(result.audioDurationMs)) {
        setError(t("waveform.loadError"));
        return;
      }
      setPrepared((old) => [...old.filter((p) => p.id !== result.id), result]);
    } else if (["FAILED", "CANCELLED"].includes(result.status))
      setError(result.error || t("waveform.loadError"));
  }, [previewQuery.data, t]);
  async function prepare() {
    setError("");
    try {
      await preparation.mutateAsync();
    } catch (cause) {
      setError(
        cause instanceof Error ? cause.message : t("waveform.loadError"),
      );
    }
  }
  function commitTimecodes() {
    const a = parseTimecode(start),
      b = parseTimecode(end);
    if (
      a === null ||
      b === null ||
      a < 0 ||
      b <= a ||
      b > durationMs ||
      b - a > 600000
    ) {
      setError(t("waveform.invalidRange"));
      return;
    }
    setError("");
    onRangeChange(a, b);
  }
  function moveSelection(event: React.PointerEvent<HTMLDivElement>) {
    if (event.type === "pointermove" && !event.buttons) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    const box = event.currentTarget.getBoundingClientRect();
    const length = endMs - startMs;
    const at = Math.max(
      0,
      Math.min(
        durationMs - length,
        ((event.clientX - box.left) / box.width) * durationMs - length / 2,
      ),
    );
    onRangeChange(Math.round(at), Math.round(at + length));
  }
  function play(next: boolean) {
    if (next && loop && (clock < startMs || clock >= endMs))
      setSeek({ atMs: startMs, sequence: Date.now() });
    setTransport((old) => ({
      playing: next,
      rate,
      sequence: old.sequence + 1,
    }));
  }
  function zoomTo(value: number) {
    setZoom(value);
    wave.current?.zoom(value);
  }
  return (
    <div className="waveform-panel">
      <div className="source-player">
        {(loading || previewQuery.isError) && (
          <div role="status">
            {loading ? (
              <Skeleton className="h-16 w-full" />
            ) : (
              previewQuery.error?.message
            )}
          </div>
        )}
        <YouTubePreview
          videoId={videoId}
          startMs={0}
          endMs={durationMs}
          sentences={[]}
          seek={seek}
          onTimeChange={setClock}
          onAvailabilityChange={setReady}
          onPlayingChange={setPlaying}
          transport={transport}
          loopRange={loop ? { startMs, endMs } : undefined}
        />
      </div>
      <div className="source-timeline">
        <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
          <span>{t("waveform.overview")}</span>
          <span className="tabular-nums">
            {timecode(clock)} / {timecode(durationMs)}
          </span>
        </div>
        <div
          className="waveform-overview"
          role="slider"
          tabIndex={0}
          aria-label={t("waveform.moveRange")}
          aria-valuemin={0}
          aria-valuemax={durationMs - (endMs - startMs)}
          aria-valuenow={startMs}
          aria-valuetext={`${timecode(startMs)} – ${timecode(endMs)}`}
          onPointerDown={moveSelection}
          onPointerMove={moveSelection}
          onKeyDown={(e) => {
            if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(e.key))
              return;
            e.preventDefault();
            const length = endMs - startMs;
            const at =
              e.key === "Home"
                ? 0
                : e.key === "End"
                  ? durationMs - length
                  : Math.max(
                      0,
                      Math.min(
                        durationMs - length,
                        startMs +
                          (e.key === "ArrowRight" ? 1 : -1) *
                            (e.shiftKey ? 1000 : 10000),
                      ),
                    );
            onRangeChange(at, at + length);
          }}
        >
          {prepared.length ? (
            <svg
              viewBox="0 0 1000 72"
              preserveAspectRatio="none"
              aria-hidden="true"
            >
              {prepared.map((segment) => (
                <path
                  key={segment.id}
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1"
                  d={segment.peaks
                    .filter(
                      (_, i) =>
                        i %
                          Math.max(
                            1,
                            Math.floor(segment.peaks.length / 1000),
                          ) ===
                        0,
                    )
                    .map((peak, i, all) => {
                      const x =
                        ((segment.startMs +
                          (i / all.length) * (segment.audioDurationMs ?? 0)) /
                          durationMs) *
                        1000;
                      return `M${x},${36 - peak * 32}v${peak * 64}`;
                    })
                    .join(" ")}
                />
              ))}
            </svg>
          ) : null}
          <div
            className="waveform-overview-selection"
            style={{
              left: `${(startMs / durationMs) * 100}%`,
              width: `${((endMs - startMs) / durationMs) * 100}%`,
            }}
          />
          {(["start", "end"] as const).map((edge) => (
            <input
              key={edge}
              className="waveform-range-handle"
              type="range"
              min={0}
              max={durationMs}
              step={1}
              value={edge === "start" ? startMs : endMs}
              aria-label={t(`waveform.${edge}Handle`)}
              aria-valuetext={timecode(edge === "start" ? startMs : endMs)}
              onPointerDown={(event) => event.stopPropagation()}
              onPointerMove={(event) => event.stopPropagation()}
              onKeyDown={(event) => event.stopPropagation()}
              onChange={(event) => {
                const at = Number(event.target.value);
                if (edge === "start")
                  onRangeChange(Math.max(endMs - 600000, Math.min(at, endMs - 1)), endMs);
                else onRangeChange(startMs, Math.min(startMs + 600000, Math.max(at, startMs + 1)));
              }}
            />
          ))}
        </div>
        <p className="text-xs text-muted-foreground">
          {t("waveform.overviewHint")}
        </p>
        <div className="waveform-timecodes">
          {[
            ["start", start, setStart],
            ["end", end, setEnd],
          ].map(([key, value, update]) => (
            <label key={key as string}>
              {t(`waveform.${key}`)}
              <Input
                value={value as string}
                inputMode="text"
                spellCheck={false}
                aria-label={t(`waveform.${key}`)}
                onChange={(e) =>
                  (update as (v: string) => void)(e.target.value)
                }
                onBlur={commitTimecodes}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    commitTimecodes();
                  }
                }}
              />
            </label>
          ))}
          <Badge variant="secondary">{timecode(endMs - startMs)}</Badge>
        </div>
        <div className="waveform-actions waveform-prepare">
          {actionsContainer ? (
            createPortal(
              <Button
                type="button"
                variant="outline"
                disabled={!valid || !inputValid || loading}
                onClick={() => void prepare()}
              >
                {loading ? (
                  <LoaderCircle
                    className="animate-spin"
                    data-icon="inline-start"
                  />
                ) : matches ? (
                  <Check data-icon="inline-start" />
                ) : (
                  <AudioLines data-icon="inline-start" />
                )}
                {t(loading ? "waveform.preparing" : "waveform.confirmRange")}
              </Button>,
              actionsContainer,
            )
          ) : (
            <Button
              type="button"
              variant="outline"
              disabled={!valid || !inputValid || loading}
              onClick={() => void prepare()}
            >
              {loading ? (
                <LoaderCircle
                  className="animate-spin"
                  data-icon="inline-start"
                />
              ) : matches ? (
                <Check data-icon="inline-start" />
              ) : (
                <AudioLines data-icon="inline-start" />
              )}
              {t(loading ? "waveform.preparing" : "waveform.confirmRange")}
            </Button>
          )}

          <span className="text-xs text-muted-foreground">
            {t("waveform.recommendation")}
          </span>
        </div>
        {loading && (
          <p role="status" className="text-sm text-muted-foreground">
            {t(`waveform.steps.${preview?.step}`, {
              defaultValue: t("waveform.preparing"),
            })}
          </p>
        )}
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        {preview?.peaks.length ? (
          <p className="text-xs text-muted-foreground">
            {t("waveform.preparedWindow", {
              start: timecode(preview.startMs),
              end: timecode(preview.startMs + (preview.audioDurationMs ?? 0)),
            })}
          </p>
        ) : null}
        <div className="waveform-detail">
          <div ref={host} data-testid="waveform-canvas" />
          {!preview?.peaks.length && (
            <p className="p-6 text-sm text-muted-foreground">
              {t("waveform.noWaveform")}
            </p>
          )}
        </div>
        {preview?.peaks.length && !matches ? (
          <p className="text-sm text-warning">{t("waveform.unprepared")}</p>
        ) : null}
        <div className="waveform-actions">
          <Button
            type="button"
            variant="outline"
            size="icon"
            disabled={!ready}
            aria-label={t(playing ? "waveform.pause" : "waveform.play")}
            onClick={() => play(!playing)}
          >
            {playing ? <Pause /> : <Play />}
          </Button>
          <Button
            type="button"
            variant={loop ? "secondary" : "ghost"}
            aria-pressed={loop}
            onClick={() => setLoop((v) => !v)}
          >
            <Repeat2 data-icon="inline-start" />
            {t("waveform.loop")}
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label={t("waveform.zoomOut")}
            disabled={!preview?.peaks.length || zoom === 0}
            onClick={() => zoomTo(Math.max(0, zoom / 2 < 2 ? 0 : zoom / 2))}
          >
            <ZoomOut />
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label={t("waveform.zoomIn")}
            disabled={!preview?.peaks.length || zoom >= 256}
            onClick={() => zoomTo(zoom ? zoom * 2 : 8)}
          >
            <ZoomIn />
          </Button>
          {[0.75, 1, 1.25].map((value) => (
            <Button
              type="button"
              key={value}
              variant={rate === value ? "secondary" : "ghost"}
              size="sm"
              aria-pressed={rate === value}
              onClick={() => {
                setRate(value);
                setTransport((old) => ({
                  playing,
                  rate: value,
                  sequence: old.sequence + 1,
                }));
              }}
            >
              {value}×
            </Button>
          ))}
        </div>
      </div>
    </div>
  );
}
