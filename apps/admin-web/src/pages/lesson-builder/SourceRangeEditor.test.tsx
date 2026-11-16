import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useState, type ReactNode } from 'react';
import { I18nextProvider } from 'react-i18next';
import { i18n } from '@/i18n';
import { TooltipProvider } from '@/components/ui/tooltip';
import { SourceRangeEditor } from './SourceRangeEditor';

const model = vi.hoisted(() => ({ player: null as null | { onTimeChange: (at: number) => void; seek: {atMs: number} | null; transport: {rate:number}; loopRange?: {startMs: number; endMs: number} }, range: null as null | {startMs: number | null; endMs: number | null} }));
vi.mock('./YouTubePreview', async () => {
  const { useEffect } = await import('react');
  return { YouTubePreview: (props: NonNullable<typeof model.player> & {onAvailabilityChange: (ready: boolean) => void}) => {
    model.player = props;
    useEffect(() => props.onAvailabilityChange(true), [props.onAvailabilityChange]);
    return <div data-testid="video" />;
  } };
});
vi.mock('./RangeTimeline', () => ({ RangeTimeline: (props: NonNullable<typeof model.range> & {playbackControls?:ReactNode; selectionControls?:ReactNode}) => { model.range = props; return <div data-testid="timeline"><div role="toolbar">{props.playbackControls}{props.selectionControls}</div></div>; } }));
afterEach(cleanup);
async function setup(initialPositionMs = 15000, durationMs = 1200000) {
  const onChange = vi.fn(), onValidityChange = vi.fn();
  function Harness() {
    const [range, setRange] = useState<[number | null, number | null]>([null, null]);
    return <SourceRangeEditor videoId="abcdefghijk" durationMs={durationMs} initialPositionMs={initialPositionMs}
      startMs={range[0]} endMs={range[1]} onValidityChange={onValidityChange}
      onRangeChange={(a, b) => { onChange(a, b); setRange([a, b]); }} />;
  }
  await act(async () => { render(<I18nextProvider i18n={i18n}><TooltipProvider><Harness /></TooltipProvider></I18nextProvider>); });
  return { onChange, onValidityChange };
}
it('starts without a selection and marks the YouTube clock, not a fixed duration', async () => {
  const { onChange, onValidityChange } = await setup();
  expect(screen.getByLabelText('Start')).toHaveValue('');
  expect(screen.getByLabelText('End')).toHaveValue('');
  expect(screen.getByRole('button', {name: 'Set end'})).toBeDisabled();
  expect(screen.getByRole('button', {name: 'Loop selection'})).toBeDisabled();
  expect(onValidityChange).toHaveBeenLastCalledWith(false);
  expect(model.player?.seek?.atMs).toBe(15000);
  fireEvent.click(screen.getByRole('button', {name: 'Set start'}));
  expect(onChange).toHaveBeenLastCalledWith(15000, null);
  expect(screen.getByRole('button', {name: 'Set end'})).toBeEnabled();
  act(() => model.player!.onTimeChange(47000));
  fireEvent.click(screen.getByRole('button', {name: 'Set end'}));
  expect(onChange).toHaveBeenLastCalledWith(15000, 47000);
  expect(onValidityChange).toHaveBeenLastCalledWith(true);
  expect(model.range).toMatchObject({startMs:15000, endMs:47000});
});
it('keeps the previous segment when a mark is reversed or exceeds ten minutes', async () => {
  const { onChange } = await setup();
  fireEvent.click(screen.getByRole('button', {name: 'Set start'}));
  for (const at of [15000, 10000, 615001, 1200000]) {
    act(() => model.player!.onTimeChange(at));
    fireEvent.click(screen.getByRole('button', {name: 'Set end'}));
    expect(onChange).toHaveBeenLastCalledWith(15000, null);
    expect(screen.getByRole('alert')).toBeInTheDocument();
  }
  act(() => model.player!.onTimeChange(615000));
  fireEvent.click(screen.getByRole('button', {name: 'Set end'}));
  expect(onChange).toHaveBeenLastCalledWith(15000, 615000);
  act(() => model.player!.onTimeChange(700000));
  fireEvent.click(screen.getByRole('button', {name: 'Set start'}));
  expect(onChange).toHaveBeenLastCalledWith(15000, 615000);
  expect(screen.getByLabelText('Start')).toHaveValue('00:15.000');
});
it('accepts typed pending start and complete timecodes and blocks malformed drafts', async () => {
  const { onChange, onValidityChange } = await setup();
  fireEvent.change(screen.getByLabelText('Start'), {target:{value:'01:00.000'}});
  fireEvent.blur(screen.getByLabelText('Start'));
  expect(onChange).toHaveBeenLastCalledWith(60000, null);
  fireEvent.change(screen.getByLabelText('End'), {target:{value:'02:00.000'}});
  fireEvent.keyDown(screen.getByLabelText('End'), {key:'Enter'});
  expect(onChange).toHaveBeenLastCalledWith(60000, 120000);
  fireEvent.change(screen.getByLabelText('End'), {target:{value:'bad'}});
  fireEvent.blur(screen.getByLabelText('End'));
  expect(onValidityChange).toHaveBeenLastCalledWith(false);
  expect(onChange).toHaveBeenLastCalledWith(60000, 120000);
  fireEvent.change(screen.getByLabelText('End'), {target:{value:'03:00.000'}});
  fireEvent.blur(screen.getByLabelText('End'));
  expect(onValidityChange).toHaveBeenLastCalledWith(true);
});
it('allows playback before selection and only loops a complete range', async () => {
  await setup();
  fireEvent.click(screen.getByRole('button', {name:'Play'}));
  expect(model.player?.loopRange).toBeUndefined();
  fireEvent.click(screen.getByRole('button', {name:'Set start'}));
  act(() => model.player!.onTimeChange(45000));
  fireEvent.click(screen.getByRole('button', {name:'Set end'}));
  fireEvent.click(screen.getByRole('button', {name:'Loop selection'}));
  expect(model.player?.loopRange).toEqual({startMs:15000, endMs:45000});
});
it('selects the whole video, including exactly ten minutes, without changing the playback position', async () => {
  const { onChange, onValidityChange } = await setup(15000, 600000);
  const button = screen.getByRole('button', { name: 'Select full video' });
  expect(button).toHaveAttribute('aria-pressed', 'false');
  fireEvent.click(button);
  expect(onChange).toHaveBeenLastCalledWith(0, 600000);
  expect(onValidityChange).toHaveBeenLastCalledWith(true);
  expect(screen.getByLabelText('Start')).toHaveValue('00:00.000');
  expect(screen.getByLabelText('End')).toHaveValue('10:00.000');
  expect(button).toHaveAttribute('aria-pressed', 'true');
  expect(model.player?.seek?.atMs).toBe(15000);
  fireEvent.change(screen.getByLabelText('End'), {target:{value:'bad'}});
  fireEvent.blur(screen.getByLabelText('End'));
  expect(onValidityChange).toHaveBeenLastCalledWith(false);
  fireEvent.click(button);
  expect(screen.getByLabelText('End')).toHaveValue('10:00.000');
  expect(onValidityChange).toHaveBeenLastCalledWith(true);
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});
it('hides full-video selection above ten minutes and explains how to continue', async () => {
  await setup(0, 600001);
  expect(screen.queryByRole('button', { name: 'Select full video' })).not.toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('This video is longer than 10 minutes');
  expect(screen.getByLabelText('Start')).toHaveValue('');
  expect(screen.getByLabelText('End')).toHaveValue('');
});
it('groups source actions in one toolbar and presents help as separate points', async () => {
  await setup(0, 600000);
  const toolbar = within(screen.getByRole('toolbar'));
  for (const name of ['Play', 'Loop selection', 'Set start', 'Set end', 'Select full video', 'How to select a segment'])
    expect(toolbar.getByRole('button', {name})).toBeInTheDocument();
  expect(toolbar.getByRole('combobox', {name:'Playback speed'})).toHaveTextContent('1×');
  fireEvent.click(toolbar.getByRole('button', {name:'How to select a segment'}));
  const help = within(screen.getByRole('dialog'));
  expect(help.getAllByRole('listitem')).toHaveLength(6);
  expect(help.getByText(/Drag the colored selection/)).toBeInTheDocument();
  expect(help.getByText(/Each lesson uses one segment of up to 10 minutes/)).toBeInTheDocument();
});
it('changes playback speed through the compact picker', async () => {
  const scrollIntoView = HTMLElement.prototype.scrollIntoView;
  HTMLElement.prototype.scrollIntoView = vi.fn();
  try {
    await setup();
    fireEvent.keyDown(screen.getByRole('combobox', {name:'Playback speed'}), {key:'ArrowDown'});
    fireEvent.click(screen.getByRole('option', {name:'0.75×'}));
    expect(model.player?.transport.rate).toBe(0.75);
  } finally { HTMLElement.prototype.scrollIntoView = scrollIntoView; }
});
