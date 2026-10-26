import { act, cleanup, render } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { I18nextProvider } from "react-i18next";
import { i18n } from "@/i18n";
import { YouTubePreview } from "./YouTubePreview";
afterEach(() => {
  cleanup();
  delete window.YT;
  vi.useRealTimers();
});
it("shows an unstarted seek immediately and applies it when playback actually starts", async () => {
  vi.useFakeTimers();
  let state = -1;
  let clock = 0;
  const seekTo = vi.fn((seconds: number) => {
    if (state === 1) clock = seconds;
  });
  window.YT = {
    Player: class {
      constructor(_host: HTMLElement, options: any) {
        queueMicrotask(() => options.events.onReady());
      }
      destroy() {}
      getCurrentTime() {
        return clock;
      }
      getPlayerState() {
        return state;
      }
      pauseVideo() {
        state = 2;
      }
      playVideo() {}
      setPlaybackRate() {}
      seekTo(seconds: number) {
        seekTo(seconds);
      }
    },
  };
  const notify = vi.fn();
  const props = {
    videoId: "abcdefghijk",
    startMs: 0,
    endMs: 100000,
    sentences: [],
    seek: null,
    onTimeChange: notify,
  };
  const view = render(
    <I18nextProvider i18n={i18n}>
      <YouTubePreview {...props} />
    </I18nextProvider>,
  );
  await act(async () => {});
  view.rerender(
    <I18nextProvider i18n={i18n}>
      <YouTubePreview {...props} seek={{ atMs: 8000, sequence: 1 }} />
    </I18nextProvider>,
  );
  await act(async () => {
    await vi.advanceTimersByTimeAsync(200);
  });
  expect(notify).toHaveBeenLastCalledWith(8000);
  view.rerender(
    <I18nextProvider i18n={i18n}>
      <YouTubePreview
        {...props}
        seek={{ atMs: 8000, sequence: 1 }}
        transport={{ playing: true, rate: 1, sequence: 1 }}
      />
    </I18nextProvider>,
  );
  state = 1;
  await act(async () => {
    await vi.advanceTimersByTimeAsync(200);
  });
  expect(seekTo).toHaveBeenLastCalledWith(8);
  expect(clock).toBe(8);
});
