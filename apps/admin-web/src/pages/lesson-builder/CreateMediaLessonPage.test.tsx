import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, Link, MemoryRouter, Route, RouterProvider, Routes, useSearchParams } from 'react-router-dom';
import { I18nextProvider } from 'react-i18next';
import { i18n } from '@/i18n';
import userEvent from '@testing-library/user-event';
import { CreateLessonPage } from './CreateLessonPage';
import { CreateMediaLessonPage } from './CreateMediaLessonPage';
import * as authoring from './lesson-builder.api';
import type { LessonDraft, SourceInspection } from './lesson-builder.types';
import * as catalog from '@/pages/lessons/catalog.api';

vi.mock('./lesson-builder.api', async original => ({...(await original<typeof import('./lesson-builder.api')>()),fetchVoices:vi.fn(),inspectSource:vi.fn(),createDraft:vi.fn(),createTextDraft:vi.fn(),synthesizeText:vi.fn(),uploadAudio:vi.fn(),fetchAudioSource:vi.fn(),createAudioDraft:vi.fn()}));
vi.mock('@/pages/lessons/catalog.api', async original => ({...(await original<typeof import('@/pages/lessons/catalog.api')>()),fetchCategories:vi.fn()}));
vi.mock('./SourceRangeEditor', () => ({SourceRangeEditor: ({onRangeChange}: {onRangeChange:(start:number,end:number)=>void}) => <button type="button" onClick={()=>onRangeChange(500,1500)}>Choose range</button>}));
let client: QueryClient;
beforeEach(async () => {
  vi.clearAllMocks(); await i18n.changeLanguage('en');
  client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});
  vi.mocked(catalog.fetchCategories).mockResolvedValue([]);
  vi.mocked(authoring.fetchVoices).mockResolvedValue({defaultVoice:'bf_emma',voices:[{id:'bf_emma',name:'Emma',accent:'UK',gender:'FEMALE',sampleAvailable:false}],sampleText:'Example.',sampleJobId:null,sampleStatus:null,sampleProgress:0,sampleError:null});
});
afterEach(()=>{cleanup();client.clear();vi.restoreAllMocks();vi.unstubAllGlobals();});
function TestSource({type}:{type:'text'|'audio'}) {
  const [params]=useSearchParams();
  return params.has('draft') ? <h1>Editor</h1> : <CreateMediaLessonPage type={type} />;
}
function show(type:'text'|'audio',query='') {
  render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={[`/lessons/new${query}`]}><Routes><Route path="/lessons/new" element={<TestSource type={type} />} /><Route path="/lessons/:id" element={<h1>Editor</h1>} /></Routes></MemoryRouter></I18nextProvider></QueryClientProvider>);
}
function CreateRoute() {
  const [params] = useSearchParams();
  return params.get('draft') ? <h1>Editor</h1> : <CreateLessonPage />;
}
function showCreateRoute(initialEntries: string[]) {
  vi.stubGlobal('AbortController', transferableAbortController().constructor);
  const router = createMemoryRouter([{ path: '/lessons/new', element: <CreateRoute /> }], { initialEntries });
  render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><RouterProvider router={router} /></I18nextProvider></QueryClientProvider>);
  return router;
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
it('keeps selections across category searches when creating a lesson', async () => {
  vi.mocked(catalog.fetchCategories).mockResolvedValue(Array.from({length:24},(_,index)=>({id:`category-${index}`,name:`Category ${index}`,lessonCount:0})));
  vi.mocked(authoring.createTextDraft).mockResolvedValue({id:'categorized'} as LessonDraft);
  show('text');
  await userEvent.click(await screen.findByRole('checkbox',{name:'Category 0'}));
  await userEvent.type(screen.getByRole('textbox',{name:'Search categories'}),'Category 23');
  expect(screen.queryByRole('checkbox',{name:'Category 0'})).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('checkbox',{name:'Category 23'}));
  await userEvent.clear(screen.getByRole('textbox',{name:'Search categories'}));
  expect(screen.getByRole('checkbox',{name:'Category 0'})).toBeChecked();
  expect(screen.getByRole('checkbox',{name:'Category 23'})).toBeChecked();
  await userEvent.type(screen.getByLabelText('English text'),'Keep both categories.');
  await userEvent.click(screen.getByRole('button',{name:'Save text draft'}));
  await screen.findByRole('heading',{name:'Editor'});
  expect(authoring.createTextDraft).toHaveBeenCalledWith(expect.objectContaining({categoryIds:['category-0','category-23']}));
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

it('keeps the selected audio range after a failed replacement and allows reselecting the same file', async () => {
  vi.mocked(authoring.fetchAudioSource).mockResolvedValue({id:'source-1',durationMs:300000,contentType:'audio/mpeg'});
  vi.mocked(authoring.uploadAudio).mockRejectedValueOnce(new Error('Upload unavailable')).mockResolvedValueOnce({id:'source-2',durationMs:2000,contentType:'audio/mpeg'});
  show('audio','?type=audio&source=source-1&start=500&end=1500');
  const input = await screen.findByLabelText('Audio file');
  const file = new File(['audio'],'replacement.mp3',{type:'audio/mpeg'});
  await userEvent.upload(input,file);
  await screen.findByRole('alert');
  expect(screen.getByText('00:00.500–00:01.500')).toBeVisible();
  await userEvent.upload(input,file);
  await waitFor(() => expect(authoring.uploadAudio).toHaveBeenCalledTimes(2));
});

it.each([
  ['youtube', 'YouTube URL', 'https://www.youtube.com/watch?v=abcdefghijk'],
  ['text', 'English text', 'Keep my lesson text'],
  ['audio', 'Title', 'Keep my recording title'],
])('guards unsaved %s input on route navigation and reload', async (type, name, value) => {
  vi.stubGlobal('AbortController', transferableAbortController().constructor);
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  const router = createMemoryRouter([
    {path:'/lessons/new',element:<><Link to="/lessons">Leave</Link><CreateLessonPage /></>},
    {path:'/lessons',element:<h1>Catalog</h1>},
  ],{initialEntries:[`/lessons/new?type=${type}`]});
  render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><RouterProvider router={router} /></I18nextProvider></QueryClientProvider>);
  fireEvent.change(screen.getByLabelText(name),{target:{value}});
  await userEvent.click(screen.getByRole('link',{name:'Leave'}));
  expect(confirm).toHaveBeenCalledOnce();
  expect(screen.getByLabelText(name)).toHaveValue(value);
  const unload = new Event('beforeunload',{cancelable:true});
  window.dispatchEvent(unload);
  expect(unload.defaultPrevented).toBe(true);
  confirm.mockReturnValue(true);
  await userEvent.click(screen.getByRole('link',{name:'Leave'}));
  await screen.findByRole('heading',{name:'Catalog'});
});

it.each([
  ['/lessons/new', 'YouTube URL'],
  ['/lessons/new?type=text&draft=existing', 'Editor'],
])('keeps unsaved form input until browser Back to %s is confirmed', async (previous, destination) => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  const router = showCreateRoute([previous, '/lessons/new?type=text']);
  fireEvent.change(screen.getByLabelText('English text'), { target: { value: 'Keep this draft.' } });

  await act(async () => { await router.navigate(-1); });
  expect(confirm).toHaveBeenCalledOnce();
  expect(screen.getByLabelText('English text')).toHaveValue('Keep this draft.');

  confirm.mockReturnValue(true);
  await act(async () => { await router.navigate(-1); });
  expect(screen.getByText(destination, { exact: true })).toBeVisible();
});

it('prompts once per source tab selection and keeps the form if canceled', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  showCreateRoute(['/lessons/new?type=text']);
  fireEvent.change(screen.getByLabelText('English text'), { target: { value: 'Discard this draft.' } });

  await userEvent.click(screen.getByRole('tab', { name: 'YouTube' }));
  expect(confirm).toHaveBeenCalledOnce();
  expect(screen.getByLabelText('English text')).toHaveValue('Discard this draft.');

  confirm.mockReturnValue(true);
  await userEvent.keyboard('{Enter}');
  expect(confirm).toHaveBeenCalledTimes(2);
  expect(screen.getByLabelText('YouTube URL')).toBeVisible();
});

it('allows audio range updates and navigation to the saved draft without a prompt', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  vi.mocked(authoring.fetchAudioSource).mockResolvedValue({ id: 'source-1', durationMs: 300000, contentType: 'audio/mpeg' });
  vi.mocked(authoring.createAudioDraft).mockResolvedValue({ lessonId: 'audio-1', jobId: 'job-1' });
  const router = showCreateRoute(['/lessons/new?type=audio&source=source-1']);
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Keep this audio title.' } });

  await userEvent.click(await screen.findByRole('button', { name: 'Choose range' }));
  expect(router.state.location.search).toContain('start=500&end=1500');
  expect(screen.getByLabelText('Title')).toHaveValue('Keep this audio title.');
  await userEvent.click(screen.getByRole('button', { name: 'Create lesson' }));
  await screen.findByRole('heading', { name: 'Editor' });
  expect(confirm).not.toHaveBeenCalled();
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
  let resolve!: (source:SourceInspection)=>void;
  vi.mocked(authoring.inspectSource).mockImplementation(()=>new Promise(yes=>{resolve=yes;}));
  render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={['/lessons/new']}><CreateLessonPage /></MemoryRouter></I18nextProvider></QueryClientProvider>);
  fireEvent.change(screen.getByLabelText('YouTube URL'),{target:{value:'https://www.youtube.com/watch?v=abcdefghijk'}});
  await waitFor(()=>expect(authoring.inspectSource).toHaveBeenCalledOnce());
  expect(screen.getByText('Checking video and captions…')).toBeVisible();
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
import { transferableAbortController } from 'node:util';

it.each([
  [null, 'No English captions. The lesson will use audio transcription.'],
  [{ language: 'en-GB', automatic: false }, 'Manual English captions available (en-GB).'],
  [{ language: 'en', automatic: true }, 'Auto-generated English captions available (en).'],
] as const)('checks caption availability automatically (%j)', async (englishCaptions, message) => {
  vi.mocked(authoring.inspectSource).mockResolvedValue({
    videoId: 'abcdefghijk', title: 'Example', durationMs: 45000, embedAllowed: true, usedRanges: [], englishCaptions,
  });
  vi.mocked(authoring.createDraft).mockResolvedValue({ lessonId: 'youtube-1', jobId: 'job-1' });
  showCreateRoute(['/lessons/new']);
  fireEvent.change(screen.getByLabelText('YouTube URL'), { target: { value: 'https://www.youtube.com/watch?v=abcdefghijk' } });
  expect(authoring.inspectSource).not.toHaveBeenCalled();
  expect(await screen.findByText(message)).toBeVisible();
  const prefer = screen.queryByRole('checkbox', {
    name: englishCaptions?.automatic ? 'Use auto-generated English captions' : 'Use publisher-provided English captions',
  });
  const useCaptionsByDefault = englishCaptions?.automatic === false;
  if (!englishCaptions) expect(prefer).not.toBeInTheDocument();
  else if (useCaptionsByDefault) expect(prefer).toBeChecked();
  else expect(prefer).not.toBeChecked();
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'YouTube lesson' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create lesson' }));
  await screen.findByRole('heading', { name: 'Editor' });
  expect(vi.mocked(authoring.createDraft).mock.calls[0][0].preferEnglishCaptions).toBe(useCaptionsByDefault);
});

it.each([null, { language: 'en', automatic: false }])('applies a locally supplied caption file with online track %j', async englishCaptions => {
  vi.mocked(authoring.inspectSource).mockResolvedValue({
    videoId: 'abcdefghijk', title: 'Example', durationMs: 45000, embedAllowed: true, usedRanges: [],
    englishCaptions,
  });
  vi.mocked(authoring.createDraft).mockResolvedValue({ lessonId: 'youtube-1', jobId: 'job-1' });
  showCreateRoute(['/lessons/new']);
  fireEvent.change(screen.getByLabelText('YouTube URL'), { target: { value: 'https://www.youtube.com/watch?v=abcdefghijk' } });
  const upload = await screen.findByLabelText('Upload English captions (optional)');
  const captions = 'WEBVTT\n\n00:00.000 --> 00:02.000\nHello!';
  const file = new File([captions], 'example.vtt');
  Object.defineProperty(file, 'text', { value: async () => captions });
  await userEvent.upload(upload, file);
  expect(await screen.findByText('The uploaded file takes priority over YouTube captions.')).toBeVisible();
  const prefer = screen.queryByRole('checkbox', { name: 'Use publisher-provided English captions' });
  if (englishCaptions) expect(prefer).toBeDisabled();
  else {
    expect(prefer).not.toBeInTheDocument();
    expect(screen.getByText('No English caption track on YouTube.')).toBeVisible();
  }
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'YouTube lesson' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create lesson' }));
  await screen.findByRole('heading', { name: 'Editor' });
  const draft = vi.mocked(authoring.createDraft).mock.calls[0][0];
  expect(draft.captionFile).toEqual({ format: 'vtt', text: captions });
  expect(draft).not.toHaveProperty('preferEnglishCaptions');
});

it('discards an outstanding file read when the source video changes', async () => {
  vi.mocked(authoring.inspectSource).mockResolvedValue({
    videoId: 'abcdefghijk', title: 'Example', durationMs: 45000, embedAllowed: true, usedRanges: [],
  });
  vi.mocked(authoring.createDraft).mockResolvedValue({ lessonId: 'youtube-1', jobId: 'job-1' });
  showCreateRoute(['/lessons/new']);
  const url = screen.getByLabelText('YouTube URL');
  fireEvent.change(url, { target: { value: 'https://www.youtube.com/watch?v=abcdefghijk' } });
  let resolve!: (text: string) => void;
  const file = new File(['old'], 'old.srt');
  Object.defineProperty(file, 'text', { value: () => new Promise(yes => { resolve = yes; }) });
  await userEvent.upload(await screen.findByLabelText('Upload English captions (optional)'), file);
  expect(screen.getByRole('button', { name: 'Create lesson' })).toBeDisabled();
  fireEvent.change(url, { target: { value: 'https://www.youtube.com/watch?v=12345678901' } });
  await act(async () => resolve('old'));
  await screen.findByLabelText('Upload English captions (optional)');
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'New source lesson' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create lesson' }));
  await screen.findByRole('heading', { name: 'Editor' });
  const draft = vi.mocked(authoring.createDraft).mock.calls[0][0];
  expect(draft.preferEnglishCaptions).toBe(false);
  expect(draft).not.toHaveProperty('captionFile');
});

it('ignores incomplete URLs and protects the latest inspection from a stale response', async () => {
  let resolveOld!: (source: SourceInspection) => void;
  const source = { videoId: '12345678901', title: 'New source', durationMs: 45000, embedAllowed: true, usedRanges: [] };
  vi.mocked(authoring.inspectSource)
    .mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
    .mockResolvedValueOnce(source);
  showCreateRoute(['/lessons/new']);
  const url = screen.getByLabelText('YouTube URL');
  fireEvent.change(url, { target: { value: 'https://www.youtube.com/watch?v=abc' } });
  fireEvent.blur(url);
  expect(await screen.findByText('Enter a valid YouTube video URL.')).toBeVisible();
  expect(authoring.inspectSource).not.toHaveBeenCalled();

  fireEvent.change(url, { target: { value: 'https://www.youtube.com/watch?v=abcdefghijk' } });
  await waitFor(() => expect(authoring.inspectSource).toHaveBeenCalledOnce());
  fireEvent.change(url, { target: { value: 'https://youtu.be/12345678901?si=share&t=3s' } });
  expect(await screen.findByText('No English captions. The lesson will use audio transcription.')).toBeVisible();
  expect(vi.mocked(authoring.inspectSource).mock.calls[0][1]?.aborted).toBe(true);
  expect(authoring.inspectSource).toHaveBeenCalledTimes(2);

  await act(async () => resolveOld({ ...source, videoId: 'abcdefghijk', englishCaptions: { language: 'en', automatic: false } }));
  expect(screen.queryByRole('checkbox', { name: /Use .* English captions/ })).not.toBeInTheDocument();
});

it('retries a failed source check and allows opting out of the discovered track', async () => {
  vi.mocked(authoring.inspectSource)
    .mockRejectedValueOnce(new Error('Network unavailable'))
    .mockResolvedValueOnce({
      videoId: 'abcdefghijk', title: 'Example', durationMs: 45000, embedAllowed: true, usedRanges: [],
      englishCaptions: { language: 'en', automatic: false },
    });
  vi.mocked(authoring.createDraft).mockResolvedValue({ lessonId: 'youtube-1', jobId: 'job-1' });
  showCreateRoute(['/lessons/new']);
  fireEvent.change(screen.getByLabelText('YouTube URL'), { target: { value: 'https://www.youtube.com/shorts/abcdefghijk' } });
  await userEvent.click(await screen.findByRole('button', { name: 'Retry' }));
  const prefer = await screen.findByRole('checkbox', { name: 'Use publisher-provided English captions' });
  expect(prefer).toBeChecked();
  await userEvent.click(prefer);
  fireEvent.click(screen.getByRole('button', { name: 'Create lesson' }));
  await screen.findByRole('heading', { name: 'Editor' });
  expect(authoring.inspectSource).toHaveBeenCalledTimes(2);
  expect(vi.mocked(authoring.createDraft).mock.calls[0][0].preferEnglishCaptions).toBe(false);
});
