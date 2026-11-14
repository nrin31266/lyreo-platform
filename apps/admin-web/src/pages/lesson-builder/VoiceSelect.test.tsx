import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nextProvider } from 'react-i18next';
import { i18n } from '@/i18n';
import { VoiceSelect } from './VoiceSelect';
import * as api from './lesson-builder.api';
vi.mock('./lesson-builder.api', () => ({fetchVoices: vi.fn(), fetchVoiceSample: vi.fn(), prepareVoiceSamples: vi.fn()}));
let client: QueryClient;
const choose = vi.fn();
beforeEach(async () => {
  vi.clearAllMocks(); await i18n.changeLanguage('en');
  client = new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});
  vi.mocked(api.fetchVoices).mockResolvedValue({defaultVoice:'af_heart',voices:[
    {id:'af_heart',name:'Heart',accent:'US',gender:'FEMALE',sampleAvailable:true},
    {id:'am_echo',name:'Echo',accent:'US',gender:'MALE',sampleAvailable:true},
    {id:'bm_george',name:'George',accent:'UK',gender:'MALE',sampleAvailable:false},
  ],sampleText:'Same text.',sampleJobId:null,sampleStatus:null,sampleProgress:0,sampleError:null});
  vi.mocked(api.fetchVoiceSample).mockResolvedValue(new Blob(['wav'],{type:'audio/wav'}));
  vi.stubGlobal('URL', class extends URL {static createObjectURL=vi.fn(()=> 'blob:sample');static revokeObjectURL=vi.fn();});
  vi.spyOn(HTMLMediaElement.prototype,'play').mockResolvedValue();
  vi.spyOn(HTMLMediaElement.prototype,'pause').mockImplementation(()=>{});
});
afterEach(()=>{cleanup();client.clear();vi.restoreAllMocks();vi.unstubAllGlobals();});
async function show() {
  render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><VoiceSelect value="af_heart" onChange={choose} /></I18nextProvider></QueryClientProvider>);
  const trigger=await screen.findByRole('button',{name:'Reading voice'});
  await waitFor(()=>expect(trigger).toBeEnabled());
  await userEvent.click(trigger);
}
it('previews separately from selection, stops the previous voice, and chooses by keyboard',async()=>{
  await show();
  await userEvent.click(screen.getByRole('button',{name:'Listen Heart'}));
  await screen.findByRole('button',{name:'Stop Heart'});
  expect(choose).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole('button',{name:'Listen Echo'}));
  await screen.findByRole('button',{name:'Stop Echo'});
  expect(screen.getByRole('button',{name:'Listen Heart'})).toBeEnabled();
  const select=screen.getByRole('button',{name:'Choose Echo'});select.focus();
  await userEvent.keyboard('{Enter}');
  expect(choose).toHaveBeenCalledExactlyOnceWith('am_echo');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(api.prepareVoiceSamples).not.toHaveBeenCalled();
});
it('switches accent tabs with arrow keys and disables a missing preview',async()=>{
  await show();screen.getByRole('tab',{name:'American voices'}).focus();
  await userEvent.keyboard('{ArrowRight}');
  expect(screen.getByRole('tab',{name:'British voices'})).toHaveAttribute('aria-selected','true');
  expect(screen.getByRole('button',{name:'Listen George'})).toBeDisabled();
  expect(screen.getByRole('button',{name:'Choose George'})).toBeEnabled();
});
it('reports a preview error and permits retry without changing the selected voice',async()=>{
  vi.mocked(api.fetchVoiceSample).mockRejectedValueOnce(new Error('Preview unavailable'));
  await show();await userEvent.click(screen.getByRole('button',{name:'Listen Heart'}));
  expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load this preview');
  await userEvent.click(screen.getByRole('button',{name:'Listen Heart'}));
  await waitFor(()=>expect(api.fetchVoiceSample).toHaveBeenCalledTimes(2));
  expect(choose).not.toHaveBeenCalled();
  fireEvent.ended(document.querySelector('audio')!);
});
