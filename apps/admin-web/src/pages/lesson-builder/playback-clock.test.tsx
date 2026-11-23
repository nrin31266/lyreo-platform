import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { I18nextProvider } from 'react-i18next';
import { i18n } from '@/i18n';
import { createPlaybackClock, PlaybackTime, PlaybackWords } from './playback-clock';
import type { AuthoringSentence } from './lesson-builder.types';
afterEach(cleanup);
it('updates playback leaves without rerendering the authoring form', () => {
  const clock=createPlaybackClock();const renderForm=vi.fn();
  function Form() { renderForm(); return <><input aria-label="editing" defaultValue="Keep my text" /><PlaybackTime clock={clock} /></>; }
  render(<Form />);
  act(()=>{for(let i=1;i<=100;i++) clock.set(i*100);});
  expect(renderForm).toHaveBeenCalledTimes(1);
  expect(screen.getByLabelText('editing')).toHaveValue('Keep my text');
  expect(screen.getByText('00:10.000')).toBeInTheDocument();
});
it('highlights only the current observed word', () => {
  const clock=createPlaybackClock();
  const sentence={text:'Hello world.',words:[{text:'Hello',startMs:0,endMs:500},{text:'world',startMs:500,endMs:1000}]} as AuthoringSentence;
  render(<I18nextProvider i18n={i18n}><PlaybackWords clock={clock} sentence={sentence} onSeek={()=>{}} /></I18nextProvider>);
  expect(screen.getByRole('button',{name:'Hello'})).toHaveAttribute('data-active','true');
  act(()=>clock.set(600));
  expect(screen.getByRole('button',{name:'world'})).toHaveAttribute('data-active','true');
  expect(screen.getByRole('button',{name:'Hello'})).toHaveAttribute('data-active','false');
});

it('marks only the currently spoken word, including gaps and seeks, without a sentence-wide bar', () => {
  const clock=createPlaybackClock();
  const sentence={text:'Hello world.',words:[{text:'Hello',startMs:100,endMs:300,charStart:0,charEnd:5},{text:'world',startMs:400,endMs:1100,charStart:6,charEnd:11}]} as AuthoringSentence;
  const {rerender}=render(<I18nextProvider i18n={i18n}><PlaybackWords timing clock={clock} sentence={sentence} onSeek={()=>{}} /></I18nextProvider>);
  act(()=>clock.set(600));
  expect(screen.getByRole('button',{name:'Hello'})).toHaveAttribute('data-active','false');
  expect(screen.getByRole('button',{name:'world.'})).toHaveAttribute('data-active','true');
  expect(screen.queryByText('00:00.400')).not.toBeInTheDocument();
  act(()=>clock.set(350));expect(screen.getByRole('button',{name:'world.'})).toHaveAttribute('data-active','false');
  expect(document.querySelectorAll('[data-active="true"]')).toHaveLength(0);
  act(()=>clock.set(5000));expect(screen.getByRole('button',{name:'world.'})).toHaveAttribute('data-active','false');
  rerender(<I18nextProvider i18n={i18n}><PlaybackWords timing clock={clock} sentence={{...sentence,words:[]}} onSeek={()=>{}} /></I18nextProvider>);
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
});
