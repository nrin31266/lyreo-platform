import { createRef } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nextProvider } from 'react-i18next';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { AudioPlayer } from './AudioPlayer';

beforeEach(async () => { await i18n.changeLanguage('en'); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
function mount(props: React.ComponentProps<typeof AudioPlayer> = {}) {
  const result = render(<I18nextProvider i18n={i18n}><AudioPlayer src="blob:recording" {...props} /></I18nextProvider>);
  const audio = result.container.querySelector('audio')!;
  Object.defineProperty(audio, 'duration', { value: 60, configurable: true });
  fireEvent.loadedMetadata(audio);
  return { ...result, audio };
}

it('uses themed controls, seeks within the clip and keeps external playback callbacks', () => {
  const ref = createRef<HTMLAudioElement>();
  const metadata = vi.fn();
  const { audio } = mount({ ref, startTime: 10, endTime: 30, onLoadedMetadata: metadata });
  expect(ref.current).toBe(audio);
  expect(audio).toHaveAttribute('hidden');
  expect(audio).not.toHaveAttribute('controls');
  expect(metadata).toHaveBeenCalledOnce();
  const seek = screen.getByRole('slider', { name: 'Playback position' });
  fireEvent.change(seek, { target: { value: '7.5' } });
  expect(audio.currentTime).toBe(17.5);
  expect(seek).toHaveAttribute('aria-valuetext', '0:07 / 0:20');
  audio.currentTime = 23; fireEvent.timeUpdate(audio);
  expect(seek).toHaveValue('13');
});

it('plays, pauses and reflects native playback changes', async () => {
  const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue();
  const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});
  const changed = vi.fn();
  const { audio } = mount({ onPause: changed });
  await userEvent.click(screen.getByRole('button', { name: 'Play' }));
  expect(play).toHaveBeenCalledOnce();
  Object.defineProperty(audio, 'paused', { value: false, configurable: true });
  fireEvent.play(audio);
  await userEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect(pause).toHaveBeenCalledOnce();
  Object.defineProperty(audio, 'paused', { value: true }); fireEvent.pause(audio);
  expect(changed).toHaveBeenCalledOnce();
  expect(screen.getByRole('button', { name: 'Play' })).toBeEnabled();
});

it('sets speed and volume, and restores sound from zero volume', async () => {
  const { audio } = mount();
  await userEvent.click(screen.getByRole('combobox', { name: 'Playback speed' }));
  await userEvent.click(screen.getByRole('option', { name: '1.5×' }));
  expect(audio.playbackRate).toBe(1.5);
  fireEvent.change(screen.getByRole('slider', { name: 'Volume' }), { target: { value: '0' } });
  expect(audio.volume).toBe(0);
  await userEvent.click(screen.getByRole('button', { name: 'Unmute audio' }));
  expect(audio.volume).toBe(1); expect(audio.muted).toBe(false);
  await userEvent.click(screen.getByRole('button', { name: 'Mute audio' }));
  expect(audio.muted).toBe(true);
});

it('shows load and play failures with a working reload action', async () => {
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockRejectedValue(new Error('Cannot decode'));
  const load = vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
  const { audio } = mount();
  await userEvent.click(screen.getByRole('button', { name: 'Play' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Audio could not be played');
  await userEvent.click(screen.getByRole('button', { name: 'Reload audio' }));
  expect(load).toHaveBeenCalledOnce();
  expect(screen.getByRole('button', { name: 'Play' })).toBeDisabled();
  fireEvent.loadedMetadata(audio); fireEvent.error(audio);
  expect(screen.getByRole('alert')).toBeVisible();
});

it('resets after changing files and disables seeking for unknown duration', async () => {
  const { audio, rerender } = mount();
  rerender(<I18nextProvider i18n={i18n}><AudioPlayer src="blob:replacement" /></I18nextProvider>);
  await waitFor(() => expect(screen.getByRole('button', { name: 'Play' })).toBeDisabled());
  Object.defineProperty(audio, 'duration', { value: Infinity });
  fireEvent.loadedMetadata(audio);
  expect(screen.getByRole('slider', { name: 'Playback position' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Play' })).toBeEnabled();
});

it('uses supplied Lesson controls without adding a second playback transport', () => {
  mount({ playbackControls: <button>Sentence playback</button> });
  expect(screen.getByRole('button', { name: 'Sentence playback' })).toBeVisible();
  expect(screen.queryByRole('button', { name: 'Play' })).not.toBeInTheDocument();
  expect(screen.queryByRole('combobox', { name: 'Playback speed' })).not.toBeInTheDocument();
  expect(screen.getByRole('slider', { name: 'Playback position' })).toBeEnabled();
});
