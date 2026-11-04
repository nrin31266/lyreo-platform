import { TooltipProvider } from '@/components/ui/tooltip';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { I18nextProvider } from 'react-i18next';
import { i18n } from '@/i18n';
import { TimingEditor } from './TimingEditor';
import { createPlaybackClock } from './playback-clock';
import type { AuthoringSentence } from './lesson-builder.types';

vi.mock('@tanstack/react-virtual', async importOriginal => {
  const actual=await importOriginal<typeof import('@tanstack/react-virtual')>();
  return {...actual,useVirtualizer:(options:Parameters<typeof actual.useVirtualizer>[0])=>actual.useVirtualizer({
    ...options,observeElementRect:(_,callback)=>{callback({width:800,height:300});return ()=>{};},measureElement:()=>128,
  })};
});
afterEach(cleanup);

it('bounds timing DOM, plays explicit sentence/word actions and pauses before editing', () => {
  const sentences: AuthoringSentence[]=Array.from({length:100},(_,index)=>({
    id:`s${index}`,text:`Sentence ${index}`,usage:'LEARN',issues:[],vocabulary:[],grammar:[],hints:[],notes:[],thoughtGroups:[],
    dictationEligible:true,shadowingEligible:true,dictationSelected:false,shadowingSelected:false,
    words:[{text:'Sentence',startMs:index*2000,endMs:index*2000+500,charStart:0,charEnd:8,tokenStart:0,tokenEnd:1}],
  }));
  const play=vi.fn(),pause=vi.fn(),clock=createPlaybackClock();
  render(<I18nextProvider i18n={i18n}><TooltipProvider><TimingEditor activeSentenceId="s0" onPlay={play} onPause={pause} sentences={sentences} revision={1} clock={clock} disabled={false} durationMs={200000} onSave={vi.fn()}/></TooltipProvider></I18nextProvider>);
  expect(document.querySelectorAll('.timing-item').length).toBeLessThan(15);
  expect(screen.queryByRole('button',{name:i18n.t('admin:reviewWorkspace.playSentence',{number:100})})).not.toBeInTheDocument();
  expect(document.querySelectorAll('.timing-word')).toHaveLength(1);
  fireEvent.click(screen.getByRole('button',{name:i18n.t('admin:reviewWorkspace.playSentence',{number:2})}));
  expect(play).toHaveBeenLastCalledWith('s1');
  fireEvent.click(screen.getByRole('button',{name:/Sentence 00:00.000/}));
  expect(play).toHaveBeenLastCalledWith('s0',0);
  act(()=>clock.set(100));
  expect(document.querySelector('.timing-word')).toHaveAttribute('data-active','true');
  fireEvent.click(screen.getAllByRole('button',{name:i18n.t('admin:authoringUi.editTiming')})[0]);
  expect(pause).toHaveBeenCalledOnce();
  expect(screen.getByRole('dialog')).toBeVisible();
});
