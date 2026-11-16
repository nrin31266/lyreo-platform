import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { I18nextProvider } from 'react-i18next';
import { i18n } from '@/i18n';
import { TimingEditor } from './TimingEditor';
import type { AuthoringSentence } from './lesson-builder.types';

afterEach(cleanup);
it('pauses before timing edits, preserves the opening revision and retains failed edits', async () => {
  const sentence = {id:'s0', text:'Hello.', words:[{text:'Hello',startMs:0,endMs:500,charStart:0,charEnd:5,tokenStart:0,tokenEnd:1}]} as AuthoringSentence;
  const play=vi.fn(),pause=vi.fn(),save=vi.fn().mockResolvedValueOnce(false).mockResolvedValueOnce(true);
  const editor = (revision: number) => <I18nextProvider i18n={i18n}><TimingEditor onPlay={play} onPause={pause} revision={revision} disabled={false} durationMs={1000} onSave={save}>{edit => <button onClick={() => edit(sentence)}>Timing</button>}</TimingEditor></I18nextProvider>;
  const view=render(editor(1));
  fireEvent.click(screen.getByRole('button',{name:'Timing'}));
  expect(pause).toHaveBeenCalledOnce();
  expect(screen.getByRole('dialog')).toBeVisible();
  fireEvent.click(screen.getByRole('button',{name:'Hello'}));
  expect(play).toHaveBeenCalledWith('s0',0);
  fireEvent.change(screen.getAllByRole('spinbutton')[1],{target:{value:'600'}});
  view.rerender(editor(2));
  await act(async () => {fireEvent.submit(screen.getByRole('dialog').querySelector('form')!);});
  expect(save).toHaveBeenCalledWith('s0',[expect.objectContaining({endMs:600})],1);
  expect(screen.getByRole('alert')).toBeVisible();
  expect(screen.getAllByRole('spinbutton')[1]).toHaveValue(600);
  await act(async () => {fireEvent.submit(screen.getByRole('dialog').querySelector('form')!);});
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
