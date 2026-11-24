import { useCallback, useEffect, useRef, useState } from 'react';
import { http } from '@/lib/api';

const PRE_ROLL_SECONDS = 0.12;
const TAIL_PADDING_SECONDS = 0.04;
const WAKEUP_CARRIER_AMPLITUDE = 1e-5;
const MAX_CACHED_CLIPS = 96;

const rawClipCache = new Map<string, Promise<ArrayBuffer>>();
let sharedContext: AudioContext | null = null;

function getAudioContext(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  const AudioCtx = window.AudioContext
    ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!AudioCtx) return null;
  if (!sharedContext || sharedContext.state === 'closed') {
    sharedContext = new AudioCtx();
  }
  return sharedContext;
}

function fetchClipBytes(assetId: string): Promise<ArrayBuffer> {
  const cached = rawClipCache.get(assetId);
  if (cached) return cached;
  if (rawClipCache.size >= MAX_CACHED_CLIPS) {
    const oldest = rawClipCache.keys().next().value;
    if (oldest) rawClipCache.delete(oldest);
  }
  const request = http
    .get<ArrayBuffer>(`/api/v1/admin/content-media/${assetId}/file`, { responseType: 'arraybuffer' })
    .then((response) => response.data)
    .catch((error) => {
      rawClipCache.delete(assetId);
      throw error;
    });
  rawClipCache.set(assetId, request);
  return request;
}

/**
 * Prepends a 120ms sub-audible (-100 dBFS) carrier before sample 0 so Bluetooth A2DP codecs
 * and power-saving OS DACs wake up before the initial consonant arrives, plus a 40ms tail pad.
 */
function withHardwarePreRoll(context: AudioContext, decoded: AudioBuffer): AudioBuffer {
  const preSamples = Math.round(decoded.sampleRate * PRE_ROLL_SECONDS);
  const tailSamples = Math.round(decoded.sampleRate * TAIL_PADDING_SECONDS);
  const totalSamples = preSamples + decoded.length + tailSamples;
  const padded = context.createBuffer(decoded.numberOfChannels, totalSamples, decoded.sampleRate);
  for (let channel = 0; channel < decoded.numberOfChannels; channel += 1) {
    const out = padded.getChannelData(channel);
    for (let i = 0; i < preSamples; i += 1) {
      out[i] = (i & 1) === 0 ? WAKEUP_CARRIER_AMPLITUDE : -WAKEUP_CARRIER_AMPLITUDE;
    }
    out.set(decoded.getChannelData(channel), preSamples);
  }
  return padded;
}

export type ClipPlaybackState = {
  activeId: string | null;
  loadingId: string | null;
  failedId: string | null;
  toggle: (assetId: string | null | undefined) => void;
  stop: () => void;
};

export function useVocabularyAudio(): ClipPlaybackState {
  const [activeId, setActiveId] = useState<string | null>(null);
  const [loadingId, setLoadingId] = useState<string | null>(null);
  const [failedId, setFailedId] = useState<string | null>(null);
  const sourceRef = useRef<AudioBufferSourceNode | null>(null);
  const fallbackAudioRef = useRef<HTMLAudioElement | null>(null);
  const fallbackUrlRef = useRef<string | null>(null);
  const generationRef = useRef(0);

  const stop = useCallback(() => {
    generationRef.current += 1;
    if (sourceRef.current) {
      sourceRef.current.onended = null;
      try { sourceRef.current.stop(); } catch { /* already stopped */ }
      sourceRef.current.disconnect();
      sourceRef.current = null;
    }
    if (fallbackAudioRef.current) {
      fallbackAudioRef.current.pause();
      fallbackAudioRef.current.src = '';
      fallbackAudioRef.current = null;
    }
    if (fallbackUrlRef.current) {
      URL.revokeObjectURL(fallbackUrlRef.current);
      fallbackUrlRef.current = null;
    }
    setActiveId(null);
    setLoadingId(null);
  }, []);

  useEffect(() => stop, [stop]);

  const toggle = useCallback((assetId: string | null | undefined) => {
    if (!assetId) return;
    if (activeId === assetId || loadingId === assetId) {
      stop();
      return;
    }
    stop();
    const requestGeneration = generationRef.current;
    setFailedId(null);
    setLoadingId(assetId);

    void (async () => {
      try {
        const context = getAudioContext();
        if (context && context.state === 'suspended') {
          await context.resume();
        }
        const bytes = await fetchClipBytes(assetId);
        if (generationRef.current !== requestGeneration) return;

        if (context) {
          const decoded = await context.decodeAudioData(bytes.slice(0));
          if (generationRef.current !== requestGeneration) return;
          const buffered = withHardwarePreRoll(context, decoded);
          const node = context.createBufferSource();
          node.buffer = buffered;
          node.connect(context.destination);
          node.onended = () => {
            if (generationRef.current === requestGeneration) {
              sourceRef.current = null;
              setActiveId(null);
            }
          };
          sourceRef.current = node;
          setLoadingId(null);
          setActiveId(assetId);
          node.start(0);
          return;
        }

        const url = URL.createObjectURL(new Blob([bytes], { type: 'audio/mpeg' }));
        fallbackUrlRef.current = url;
        const element = new Audio(url);
        fallbackAudioRef.current = element;
        element.onended = () => {
          if (generationRef.current === requestGeneration) stop();
        };
        setLoadingId(null);
        setActiveId(assetId);
        await element.play();
      } catch {
        if (generationRef.current === requestGeneration) {
          setLoadingId(null);
          setActiveId(null);
          setFailedId(assetId);
        }
      }
    })();
  }, [activeId, loadingId, stop]);

  return { activeId, loadingId, failedId, toggle, stop };
}
