import { TooltipProvider } from "@/components/ui/tooltip";
import { act, cleanup, fireEvent, render, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { I18nextProvider } from "react-i18next";
import { i18n } from "@/i18n";
import { YouTubePreview } from "./YouTubePreview";
import type { AuthoringSentence } from './lesson-builder.types';
afterEach(() => {
  cleanup();
  delete window.YT;
  vi.useRealTimers();
});
it('moves the existing playback controls into the shared dock without duplicating them', async () => {
  const host=document.createElement('div');document.body.append(host);
  const view=render(<I18nextProvider i18n={i18n}><TooltipProvider>
    <YouTubePreview videoId="abcdefghijk" startMs={0} endMs={10000} sentences={[]} seek={null} onTimeChange={vi.fn()} controlsHost={host}/>
  </TooltipProvider></I18nextProvider>);
  expect(within(host).getAllByRole('button',{name:i18n.t('admin:waveform.play')})).toHaveLength(1);
  expect(view.container.querySelector('.player-transport')).toBeNull();
  view.unmount();expect(host).toBeEmptyDOMElement();host.remove();
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
    <I18nextProvider i18n={i18n}><TooltipProvider>
      <YouTubePreview {...props} />
    </TooltipProvider></I18nextProvider>,
  );
  await act(async () => {});
  view.rerender(
    <I18nextProvider i18n={i18n}><TooltipProvider>
      <YouTubePreview {...props} seek={{ atMs: 8000, sequence: 1 }} />
    </TooltipProvider></I18nextProvider>,
  );
  await act(async () => {
    await vi.advanceTimersByTimeAsync(200);
  });
  expect(notify).toHaveBeenLastCalledWith(8000);
  view.rerender(
    <I18nextProvider i18n={i18n}><TooltipProvider>
      <YouTubePreview
        {...props}
        seek={{ atMs: 8000, sequence: 1 }}
        transport={{ playing: true, rate: 1, sequence: 1 }}
      />
    </TooltipProvider></I18nextProvider>,
  );
  state = 1;
  await act(async () => {
    await vi.advanceTimersByTimeAsync(200);
  });
  expect(seekTo).toHaveBeenLastCalledWith(8);
  expect(clock).toBe(8);
});

it('keeps the iframe instance when the selected interval changes', async () => {
  vi.useFakeTimers();
  let state=-1;
  const construct=vi.fn();const seek=vi.fn();
  window.YT={Player:class {
    constructor(_host:HTMLElement,options:any) {construct();queueMicrotask(()=>options.events.onReady());}
    destroy() {} getCurrentTime(){return 0;} getPlayerState(){return state;}
    pauseVideo(){state=2;} playVideo(){state=1;} setPlaybackRate(){} seekTo(seconds:number){seek(seconds);}
  }};
  const props={videoId:'abcdefghijk',startMs:0,endMs:10000,sentences:[],seek:null,onTimeChange:vi.fn()};
  const view=render(<I18nextProvider i18n={i18n}><TooltipProvider><YouTubePreview {...props}/></TooltipProvider></I18nextProvider>);
  await act(async()=>{});
  view.rerender(<I18nextProvider i18n={i18n}><TooltipProvider><YouTubePreview {...props} startMs={2000} endMs={15000}/></TooltipProvider></I18nextProvider>);
  await act(async()=>{});
  expect(construct).toHaveBeenCalledTimes(1);
  expect(seek).not.toHaveBeenCalled();
  expect(view.container.querySelector('.youtube-poster')).toHaveAttribute('src','https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg');
  fireEvent.click(view.getByRole('button',{name:i18n.t('admin:waveform.play')}));
  await act(async()=>{await vi.advanceTimersByTimeAsync(100);});
  expect(seek).toHaveBeenLastCalledWith(2);
});

it('bounds the displayed clip clock when YouTube reports a tick beyond the selected end', async () => {
  vi.useFakeTimers();
  const pause=vi.fn(),notify=vi.fn();
  window.YT={Player:class {
    constructor(_host:HTMLElement,options:any){queueMicrotask(()=>options.events.onReady());}
    destroy(){} getCurrentTime(){return 16;} getPlayerState(){return 1;}
    pauseVideo(){pause();} playVideo(){} setPlaybackRate(){} seekTo(){}
  }};
  render(<I18nextProvider i18n={i18n}><TooltipProvider><YouTubePreview videoId="abcdefghijk" startMs={10000} endMs={15000} sentences={[]} seek={null} onTimeChange={notify}/></TooltipProvider></I18nextProvider>);
  await act(async()=>{await vi.advanceTimersByTimeAsync(200);});
  expect(notify).toHaveBeenLastCalledWith(5000);
  expect(pause).toHaveBeenCalled();
});

it('starts only on intent, stops per sentence, and advances karaoke only in continuous mode', async () => {
  vi.useFakeTimers();
  let state=-1,seconds=10;
  const play=vi.fn(()=>{state=1;}),pause=vi.fn(()=>{state=2;}),seekTo=vi.fn((at:number)=>{seconds=at;});
  const construct=vi.fn();
  window.YT={Player:class {
    constructor(_host:HTMLElement,options:any){construct(options);queueMicrotask(()=>options.events.onReady());}
    destroy(){} getCurrentTime(){return seconds;} getPlayerState(){return state;}
    pauseVideo(){pause();} playVideo(){play();} setPlaybackRate(){} seekTo(at:number){seekTo(at);}
  }};
  const rows=[{id:'a',usage:'LEARN',words:[{startMs:100,endMs:1000}]},
    {id:'skip',usage:'SKIP',words:[{startMs:1200,endMs:2000}]},
    {id:'b',usage:'WATCH_ONLY',words:[{startMs:2500,endMs:4000}]}] as AuthoringSentence[];
  const changed=vi.fn(),notify=vi.fn();
  const props={videoId:'abcdefghijk',startMs:10000,endMs:15000,sentences:rows,onTimeChange:notify};
  const review={sentenceId:'a',context:'timing',onSentenceChange:changed};
  const draw=(seek:{atMs:number;sequence:number;play?:boolean}|null,context=review)=> <I18nextProvider i18n={i18n}><TooltipProvider><YouTubePreview {...props} review={context} seek={seek}/></TooltipProvider></I18nextProvider>;
  const view=render(draw(null));
  await act(async()=>{await vi.advanceTimersByTimeAsync(200);});
  expect(construct.mock.calls[0][0].playerVars.autoplay).toBe(0);
  expect(play).not.toHaveBeenCalled();expect(seekTo).not.toHaveBeenCalled();
  const request={atMs:100,sequence:1,play:true};
  view.rerender(draw(request));
  await act(async()=>{await vi.advanceTimersByTimeAsync(200);});
  expect(play).toHaveBeenCalledTimes(1);expect(seconds).toBe(10.1);
  seconds=11.2;
  await act(async()=>{await vi.advanceTimersByTimeAsync(100);});
  expect(state).toBe(2);expect(seconds).toBe(11);expect(changed).not.toHaveBeenCalled();
  fireEvent.click(view.getByRole('button',{name:i18n.t('admin:reviewWorkspace.continuousPlayback')}));
  expect(state).toBe(2);expect(play).toHaveBeenCalledTimes(1);
  fireEvent.click(view.getByRole('button',{name:i18n.t('admin:waveform.play')}));
  await act(async()=>{await vi.advanceTimersByTimeAsync(200);});
  expect(seconds).toBe(10.1);
  seconds=11.1;
  await act(async()=>{await vi.advanceTimersByTimeAsync(100);});
  expect(changed).toHaveBeenLastCalledWith('b');expect(seconds).toBe(12.5);expect(state).toBe(1);
  seconds=14.1;
  await act(async()=>{await vi.advanceTimersByTimeAsync(100);});
  expect(state).toBe(2);expect(seconds).toBe(14);
  const next={atMs:2500,sequence:2,play:true};
  const selected={...review,sentenceId:'b'};
  view.rerender(draw(next,selected));
  await act(async()=>{await vi.advanceTimersByTimeAsync(100);});
  fireEvent.click(view.getByRole('button',{name:i18n.t('admin:reviewWorkspace.loopSentence')}));
  expect(view.getByRole('button',{name:i18n.t('admin:reviewWorkspace.continuousPlayback')})).toHaveAttribute('aria-pressed','false');
  seconds=14.1;
  await act(async()=>{await vi.advanceTimersByTimeAsync(100);});
  expect(seconds).toBe(12.5);expect(state).toBe(1);
  view.rerender(draw(next,{...selected,context:'transcript'}));
  await act(async()=>{await vi.advanceTimersByTimeAsync(100);});
  expect(state).toBe(2);
  expect(view.getByRole('button',{name:i18n.t('admin:reviewWorkspace.loopSentence')})).toHaveAttribute('aria-pressed','false');
  const playsBeforeReload=play.mock.calls.length;
  view.rerender(<I18nextProvider i18n={i18n}><TooltipProvider><YouTubePreview {...props} videoId="lmnopqrstuv" review={selected} seek={next}/></TooltipProvider></I18nextProvider>);
  await act(async()=>{await vi.advanceTimersByTimeAsync(200);});
  expect(play).toHaveBeenCalledTimes(playsBeforeReload);
  expect(state).toBe(2);
});
