import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { I18nextProvider } from 'react-i18next';
import { i18n } from '@/i18n';
import { AudioPreview } from './AudioPreview';

afterEach(()=>{cleanup();vi.useRealTimers();vi.restoreAllMocks();});
it('shares the playback clock, seeks and loops the selected range', () => {
  vi.useFakeTimers();
  const clock=vi.fn();const availability=vi.fn();
  const {container,rerender}=render(<I18nextProvider i18n={i18n}><AudioPreview src="blob:audio" startMs={0} endMs={5000} sentences={[]} seek={null} loopRange={{startMs:1000,endMs:2000}} onTimeChange={clock} onAvailabilityChange={availability} /></I18nextProvider>);
  const audio=container.querySelector('audio')!;
  fireEvent.loadedMetadata(audio);expect(availability).toHaveBeenCalledWith(true);
  Object.defineProperty(audio,'paused',{value:false});audio.currentTime=0.5;
  act(()=>vi.advanceTimersByTime(50));
  expect(clock).toHaveBeenCalledWith(500);
  expect(audio.currentTime).toBe(1);
  audio.currentTime=2.1;act(()=>vi.advanceTimersByTime(50));expect(audio.currentTime).toBe(1);
  rerender(<I18nextProvider i18n={i18n}><AudioPreview src="blob:audio" startMs={0} endMs={5000} sentences={[]} seek={{atMs:3000,sequence:1}} onTimeChange={clock} /></I18nextProvider>);
  expect(audio.currentTime).toBe(3);
  expect(screen.getByRole('button',{name:'1×'})).toBeEnabled();
});

it('pauses on explicit seek and can loop at the native media end', () => {
  const pause=vi.spyOn(HTMLMediaElement.prototype,'pause').mockImplementation(()=>{});
  const play=vi.spyOn(HTMLMediaElement.prototype,'play').mockResolvedValue();
  const props={src:'blob:audio',startMs:0,endMs:5000,sentences:[],onTimeChange:vi.fn()};
  const {container,rerender}=render(<I18nextProvider i18n={i18n}><AudioPreview {...props} seek={null} /></I18nextProvider>);
  const audio=container.querySelector('audio')!;fireEvent.loadedMetadata(audio);
  pause.mockClear();
  rerender(<I18nextProvider i18n={i18n}><AudioPreview {...props} seek={{atMs:2000,sequence:1,play:false}} /></I18nextProvider>);
  expect(pause).toHaveBeenCalledOnce();expect(audio.currentTime).toBe(2);
  fireEvent.click(screen.getByRole('button',{name:i18n.t('admin:waveform.loop')}));
  audio.currentTime=5;fireEvent.ended(audio);
  expect(audio.currentTime).toBe(0);expect(play).toHaveBeenCalledOnce();
});

it('plays audio continuously through sentence gaps until the clip ends without changing currentTime', () => {
  vi.useFakeTimers();
  let paused=true;
  const pause=vi.spyOn(HTMLMediaElement.prototype,'pause').mockImplementation(()=>{paused=true;});
  const play=vi.spyOn(HTMLMediaElement.prototype,'play').mockImplementation(async()=>{paused=false;});
  const changed=vi.fn();
  const rows=[{id:'a',words:[{startMs:100,endMs:1000}]},{id:'b',words:[{startMs:2500,endMs:4000}]}] as import('./lesson-builder.types').AuthoringSentence[];
  const {container}=render(<I18nextProvider i18n={i18n}><AudioPreview src="blob:audio" startMs={0} endMs={5000} sentences={rows} seek={null} review={{sentenceId:'a',context:'timing',onSentenceChange:changed}} onTimeChange={vi.fn()} /></I18nextProvider>);
  const audio=container.querySelector('audio')!;
  Object.defineProperty(audio,'paused',{get:()=>paused,configurable:true});
  fireEvent.loadedMetadata(audio);audio.currentTime=1.1;pause.mockClear();
  fireEvent.click(screen.getByRole('button',{name:i18n.t('admin:reviewWorkspace.continuousPlayback')}));
  expect(play).not.toHaveBeenCalled();expect(audio.currentTime).toBe(1.1);
  fireEvent.click(screen.getByRole('button',{name:i18n.t('admin:waveform.play')}));
  expect(audio.currentTime).toBe(1.1);
  act(()=>vi.advanceTimersByTime(50));
  expect(changed).toHaveBeenLastCalledWith(null);expect(pause).not.toHaveBeenCalled();
  audio.currentTime=2.6;act(()=>vi.advanceTimersByTime(50));
  expect(changed).toHaveBeenLastCalledWith('b');expect(audio.currentTime).toBe(2.6);
  audio.currentTime=4.5;act(()=>vi.advanceTimersByTime(50));
  expect(audio.currentTime).toBe(4.5);expect(pause).not.toHaveBeenCalled();
  audio.currentTime=5;act(()=>vi.advanceTimersByTime(50));expect(pause).toHaveBeenCalledOnce();
});
