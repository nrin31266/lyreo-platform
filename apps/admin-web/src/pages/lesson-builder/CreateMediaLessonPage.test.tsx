import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router-dom';
import { I18nextProvider } from 'react-i18next';
import { i18n } from '@/i18n';
import userEvent from '@testing-library/user-event';
import { CreateLessonPage } from './CreateLessonPage';
import { CreateMediaLessonPage } from './CreateMediaLessonPage';
import * as authoring from './lesson-builder.api';
import type { LessonDraft } from './lesson-builder.types';
import * as catalog from '@/pages/lessons/catalog.api';

vi.mock('./lesson-builder.api', async original => ({...(await original<typeof import('./lesson-builder.api')>()),fetchVoices:vi.fn(),inspectSource:vi.fn(),createTextDraft:vi.fn(),synthesizeText:vi.fn(),uploadAudio:vi.fn(),fetchAudioSource:vi.fn(),createAudioDraft:vi.fn()}));
vi.mock('@/pages/lessons/catalog.api', async original => ({...(await original<typeof import('@/pages/lessons/catalog.api')>()),fetchCategories:vi.fn()}));
vi.mock('./SourceRangeEditor', () => ({SourceRangeEditor: ({onRangeChange}: {onRangeChange:(start:number,end:number)=>void}) => <button type="button" onClick={()=>onRangeChange(500,1500)}>Choose range</button>}));
let client: QueryClient;
beforeEach(async () => {
  vi.clearAllMocks(); await i18n.changeLanguage('en');
  client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});
  vi.mocked(catalog.fetchCategories).mockResolvedValue([]);
  vi.mocked(authoring.fetchVoices).mockResolvedValue({defaultVoice:'bf_emma',voices:[{id:'bf_emma',name:'Emma',accent:'UK',gender:'FEMALE',sampleAvailable:false}],sampleText:'Example.',sampleJobId:null,sampleStatus:null,sampleProgress:0,sampleError:null});
});
afterEach(()=>{cleanup();client.clear();});
function TestSource({type}:{type:'text'|'audio'}) {
  const [params]=useSearchParams();
  return params.has('draft') ? <h1>Editor</h1> : <CreateMediaLessonPage type={type} />;
}
function show(type:'text'|'audio',query='') {
  render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={[`/lessons/new${query}`]}><Routes><Route path="/lessons/new" element={<TestSource type={type} />} /><Route path="/lessons/:id" element={<h1>Editor</h1>} /></Routes></MemoryRouter></I18nextProvider></QueryClientProvider>);
}
it('saves text with the discovered default voice without invoking synthesis', async () => {
  vi.mocked(authoring.createTextDraft).mockResolvedValue({id:'text-1'} as LessonDraft);
  show('text');
  fireEvent.change(screen.getByLabelText('Title'),{target:{value:'Text lesson'}});
  fireEvent.change(screen.getByLabelText('English text'),{target:{value:'We learn.'}});
  await waitFor(()=>expect(screen.getByRole('button',{name:'Save text draft'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button',{name:'Save text draft'}));
  await screen.findByRole('heading',{name:'Editor'});
  expect(authoring.createTextDraft).toHaveBeenCalledWith(expect.objectContaining({title:'Text lesson',text:'We learn.',voice:'bf_emma',categoryIds:[],description:'',imageUrl:''}));
  expect(authoring.synthesizeText).not.toHaveBeenCalled();
});
it('restores the uploaded source from the URL and submits the selected range', async () => {
  vi.mocked(authoring.fetchAudioSource).mockResolvedValue({id:'source-1',durationMs:300000,contentType:'audio/mpeg'});
  vi.mocked(authoring.createAudioDraft).mockResolvedValue({lessonId:'audio-1',jobId:'job-1'});
  show('audio','?type=audio&source=source-1');
  fireEvent.change(screen.getByLabelText('Title'),{target:{value:'Audio lesson'}});
  fireEvent.click(await screen.findByRole('button',{name:'Choose range'}));
  fireEvent.click(screen.getByRole('button',{name:'Create lesson'}));
  await screen.findByRole('heading',{name:'Editor'});
  expect(authoring.createAudioDraft).toHaveBeenCalledWith(expect.objectContaining({title:'Audio lesson',sourceId:'source-1',startMs:500,endMs:1500,categoryIds:[],description:'',imageUrl:''}));
  expect(authoring.uploadAudio).not.toHaveBeenCalled();
});
it('blocks an oversized audio file before uploading', async () => {
  vi.mocked(authoring.fetchAudioSource).mockResolvedValue({id:'source-1',durationMs:300000,contentType:'audio/mpeg'});
  show('audio','?type=audio&source=source-1');
  fireEvent.change(screen.getByLabelText('Title'),{target:{value:'Audio lesson'}});
  await waitFor(()=>expect(screen.getByRole('button',{name:'Create lesson'})).toBeEnabled());
  const file=new File(['audio'],'too-large.mp3',{type:'audio/mpeg'});Object.defineProperty(file,'size',{value:30*1024*1024+1});
  fireEvent.change(screen.getByLabelText('Audio file'),{target:{files:[file]}});
  expect(await screen.findByRole('alert')).toHaveTextContent('30 MiB');
  expect(screen.getByRole('button',{name:'Create lesson'})).toBeDisabled();
  expect(authoring.uploadAudio).not.toHaveBeenCalled();
});

it('prevents switching source types until upload finishes', async () => {
  let finish!: (source: authoring.AudioSource) => void;
  vi.mocked(authoring.uploadAudio).mockImplementation(()=>new Promise(resolve=>{finish=resolve;}));
  render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={['/lessons/new?type=audio']}><CreateLessonPage /></MemoryRouter></I18nextProvider></QueryClientProvider>);
  fireEvent.change(screen.getByLabelText('Audio file'),{target:{files:[new File(['audio'],'sample.mp3',{type:'audio/mpeg'})]}});
  await waitFor(()=>expect(authoring.uploadAudio).toHaveBeenCalledOnce());
  expect(screen.getByRole('tab',{name:'Text'})).toBeDisabled();
  await userEvent.click(screen.getByRole('tab',{name:'Text'}));
  expect(screen.getByLabelText('Audio file')).toBeVisible();
  await act(async()=>finish({id:'late-source',durationMs:1000,contentType:'audio/mpeg'}));
  expect(screen.getByRole('tab',{name:'Text'})).toBeEnabled();
});

it('allows an empty title and submits shared metadata without running AI', async () => {
  vi.mocked(authoring.createTextDraft).mockResolvedValue({id:'auto-title'} as LessonDraft);
  show('text');
  fireEvent.change(screen.getByLabelText('English text'),{target:{value:'A title from the first sentence. More content.'}});
  await waitFor(()=>expect(screen.getByRole('button',{name:'Save text draft'})).toBeEnabled());
  await userEvent.click(screen.getByRole('button',{name:'Additional information'}));
  fireEvent.change(screen.getByLabelText('Description'),{target:{value:'Shared description'}});
  await userEvent.click(screen.getByRole('button',{name:'Save text draft'}));
  await screen.findByRole('heading',{name:'Editor'});
  expect(authoring.createTextDraft).toHaveBeenCalledWith(expect.objectContaining({title:'',description:'Shared description',imageUrl:''}));
  expect(authoring.synthesizeText).not.toHaveBeenCalled();
});

it('preserves draft fields while opening AI help and changes the prompt with the source tab', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true);
  render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={['/lessons/new?type=text']}><CreateLessonPage /></MemoryRouter></I18nextProvider></QueryClientProvider>);
  fireEvent.change(screen.getByLabelText('English text'), {target:{value:'Keep this draft.'}});
  fireEvent.change(screen.getByLabelText('Title'), {target:{value:'Keep this title'}});
  await userEvent.click(screen.getByRole('button', {name:'AI prompt'}));
  expect(await screen.findByRole('dialog', {name:'Draft a Text lesson with AI'})).toBeVisible();
  await userEvent.keyboard('{Escape}');
  expect(screen.getByLabelText('English text')).toHaveValue('Keep this draft.');
  expect(screen.getByLabelText('Title')).toHaveValue('Keep this title');
  for (const [tab, title] of [['Audio', 'Prepare an Audio source with AI'], ['YouTube', 'Find a YouTube video with AI']]) {
    await userEvent.click(screen.getByRole('tab', {name:tab}));
    await userEvent.click(screen.getByRole('button', {name:'AI prompt'}));
    expect(await screen.findByRole('dialog', {name:title})).toBeVisible();
    await userEvent.keyboard('{Escape}');
  }
  expect(authoring.createTextDraft).not.toHaveBeenCalled();
  expect(authoring.createAudioDraft).not.toHaveBeenCalled();
  expect(authoring.synthesizeText).not.toHaveBeenCalled();
});

it('saves the selected voice and starts speech from the primary source action', async () => {
  vi.mocked(authoring.createTextDraft).mockResolvedValue({id:'spoken',revision:7} as LessonDraft);
  vi.mocked(authoring.synthesizeText).mockResolvedValue({lessonId:'spoken',jobId:'tts-job'});
  show('text');
  fireEvent.change(screen.getByLabelText('English text'),{target:{value:'We learn English.'}});
  await waitFor(()=>expect(screen.getByRole('button',{name:'Create lesson'})).toBeEnabled());
  await userEvent.click(screen.getByRole('button',{name:'Create lesson'}));
  await screen.findByRole('heading',{name:'Editor'});
  expect(authoring.createTextDraft).toHaveBeenCalledWith(expect.objectContaining({voice:'bf_emma'}));
  expect(authoring.createTextDraft).toHaveBeenCalledWith(expect.objectContaining({startProcessing:true}));
  expect(authoring.synthesizeText).not.toHaveBeenCalled();
});

it('keeps source inputs stable and preserves them when creation fails', async()=>{
  let reject!: (error:Error)=>void;
  vi.mocked(authoring.createTextDraft).mockImplementation(()=>new Promise((_resolve,no)=>{reject=no;}));
  show('text');
  fireEvent.change(screen.getByLabelText('English text'),{target:{value:'Keep this text.'}});
  await waitFor(()=>expect(screen.getByRole('button',{name:'Create lesson'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button',{name:'Create lesson'}));
  await waitFor(()=>expect(authoring.createTextDraft).toHaveBeenCalledOnce());
  expect(screen.getByLabelText('English text')).toBeDisabled();
  expect(screen.getByRole('button',{name:'Starting lesson…'})).toBeDisabled();
  expect(screen.getByRole('progressbar')).toBeInTheDocument();
  await act(async()=>reject(new Error('Network unavailable')));
  expect(screen.getByLabelText('English text')).toHaveValue('Keep this text.');
  expect(await screen.findByRole('button',{name:'Create lesson'})).toBeEnabled();
});
it('keeps editable metadata visible before, during and after YouTube inspection',async()=>{
  let resolve!: (source:import('./lesson-builder.types').SourceInspection)=>void;
  vi.mocked(authoring.inspectSource).mockImplementation(()=>new Promise(yes=>{resolve=yes;}));
  render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={['/lessons/new']}><CreateLessonPage /></MemoryRouter></I18nextProvider></QueryClientProvider>);
  fireEvent.change(screen.getByLabelText('YouTube URL'),{target:{value:'https://www.youtube.com/watch?v=abcdefghijk'}});
  fireEvent.click(screen.getByRole('button',{name:'Search'}));
  await waitFor(()=>expect(authoring.inspectSource).toHaveBeenCalledOnce());
  expect(screen.getByRole('button',{name:'Create lesson'})).toBeDisabled();
  expect(document.querySelector('.source-range-skeleton .source-player')).toBeInTheDocument();
  expect(document.querySelector('.source-metadata-pane .source-details-skeleton')).not.toBeInTheDocument();
  expect(screen.getByRole('textbox', {name:'Title'})).toBeEnabled();
  fireEvent.change(screen.getByRole('textbox', {name:'Title'}), {target:{value:'Keep my title'}});
  await act(async()=>resolve({videoId:'abcdefghijk',title:'Example',durationMs:45000,embedAllowed:true,usedRanges:[]}));
  expect(document.querySelector('.source-range-skeleton')).not.toBeInTheDocument();
  expect(screen.getByRole('textbox', {name:'Title'})).toHaveValue('Keep my title');
  expect(screen.getByLabelText('Title')).toBeVisible();
});

it('shows the YouTube settings panel before entering a URL without starting inference', async () => {
  render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={['/lessons/new']}><CreateLessonPage /></MemoryRouter></I18nextProvider></QueryClientProvider>);
  expect(screen.getByRole('textbox', {name:'Title'})).toBeEnabled();
  expect(screen.getByRole('checkbox', {name:'Translation'})).toBeChecked();
  expect(screen.getByRole('button', {name:'Create lesson'})).toBeDisabled();
  expect(authoring.inspectSource).not.toHaveBeenCalled();
});
