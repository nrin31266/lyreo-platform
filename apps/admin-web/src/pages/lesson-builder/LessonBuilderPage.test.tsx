import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useEffect } from 'react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { adminI18nResources } from '@lyreo/i18n';
import { TooltipProvider } from '@/components/ui/tooltip';
import { api as request } from '@/lib/api';
import { LessonBuilderPage } from './LessonBuilderPage';
import * as api from './lesson-builder.api';
import * as catalog from '@/pages/lessons/catalog.api';
import type { LessonDraft } from './lesson-builder.types';
vi.mock('./lesson-builder.api',()=>({fetchDraft:vi.fn(),cancelChain:vi.fn(),saveChanges:vi.fn(),runLesson:vi.fn(),fetchReadiness:vi.fn(),publishLesson:vi.fn(),duplicateLesson:vi.fn(),fetchDiagnostics:vi.fn(),saveTiming:vi.fn()}));
vi.mock('@/lib/api', async original => ({ ...await original<typeof import('@/lib/api')>(), api: vi.fn() }));
vi.mock('@/pages/lessons/catalog.api',async original=>({...await original<typeof import('@/pages/lessons/catalog.api')>(),fetchCategories:vi.fn().mockResolvedValue([]),fetchMetadata:vi.fn().mockResolvedValue({id:'lesson-a',title:'A lesson',categories:[],description:'',imageUrl:'',version:0}),saveMetadata:vi.fn()}));
vi.mock('./AudioPreview',()=>({LessonPlayer:({review,onAvailabilityChange}:{review:{sentenceId:string|null};onAvailabilityChange?: (available:boolean)=>void})=> { useEffect(() => onAvailabilityChange?.(true), [onAvailabilityChange]); return <div data-testid="player" data-selected={review.sentenceId}>Player</div>; }}));
vi.mock('./VoiceSelect',()=>({VoiceSelect:()=>null}));
const original: LessonDraft={id:'lesson-a',title:'A lesson',videoId:'abcdefghijk',sourceType:'YOUTUBE',sourceReference:'source',startMs:0,endMs:10000,audioReady:true,revision:1,transcriptRevision:1,status:'CONTENT_REVIEW',timingStatus:'VALID',alignmentRequired:false,contentComplete:true,contentNeeded:false,approvedRevision:null,karaokeReviewedRevision:null,semanticRevision:null,jobs:[],sentences:[{id:'s1',text:'Hello world.',usage:'LEARN',issues:[],vocabulary:[],grammar:[],hints:[],notes:[],thoughtGroups:[],dictationEligible:true,shadowingEligible:true,dictationSelected:true,shadowingSelected:true,translation:'Xin chào.',words:[{text:'Hello',startMs:0,endMs:500,charStart:0,charEnd:5,tokenStart:0,tokenEnd:1},{text:'world',startMs:500,endMs:1000,charStart:6,charEnd:11,tokenStart:1,tokenEnd:2}]}]};
beforeEach(()=>{vi.clearAllMocks();vi.mocked(api.fetchDraft).mockResolvedValue(structuredClone(original));vi.mocked(api.fetchReadiness).mockResolvedValue({ready:true,blockers:[]});vi.mocked(api.saveChanges).mockImplementation(async(_id,draft)=>({...draft,revision:2}));});
afterEach(cleanup);
async function show(draft?:LessonDraft) {if(draft)vi.mocked(api.fetchDraft).mockResolvedValue(draft);const i18n=createInstance();await i18n.use(initReactI18next).init({resources:adminI18nResources,lng:'en',ns:['admin']});const client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><TooltipProvider><MemoryRouter initialEntries={['/lessons/lesson-a']}><Routes><Route path="/lessons/:lessonId" element={<div id="main-content"><LessonBuilderPage/></div>}/></Routes></MemoryRouter></TooltipProvider></I18nextProvider></QueryClientProvider>);await screen.findByRole('textbox',{name:'Sentence 1'});return client;}
it('opens with directly editable translation and selections without inference or review gates',async()=>{await show();expect(screen.getByRole('textbox',{name:'Translation'})).toHaveValue('Xin chào.');expect(screen.getByRole('checkbox',{name:'Shadowing'})).toBeChecked();expect(screen.queryByText(/checked all|listened to all|pronunciation checked/i)).not.toBeInTheDocument();expect(api.runLesson).not.toHaveBeenCalled();expect(screen.getByRole('button',{name:'Update affected parts'})).toBeDisabled();});
it('saves sentence edits in one batch with Ctrl+S and never invokes AI',async()=>{await show();fireEvent.change(screen.getByRole('textbox',{name:'Translation'}),{target:{value:'Bản sửa'}});fireEvent.keyDown(document,{key:'s',ctrlKey:true});await waitFor(()=>expect(api.saveChanges).toHaveBeenCalledTimes(1));expect(vi.mocked(api.saveChanges).mock.calls[0][1].sentences[0].translation).toBe('Bản sửa');expect(api.runLesson).not.toHaveBeenCalled();});
it('splits at the transcript caret and merges with the next sentence using row buttons', async () => {
  await show();
  const input = screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Sentence 1' });
  const firstRow = within(screen.getByRole('listitem'));
  const splitButton = firstRow.getByRole('button', { name: 'Split at cursor' });
  const mergeButton = firstRow.getByRole('button', { name: 'Merge with next' });
  expect(mergeButton).toBeDisabled();
  await userEvent.click(input);
  input.setSelectionRange(2, 2);
  await userEvent.click(splitButton);
  expect(firstRow.getByRole('alert')).toHaveTextContent('Place the cursor between two words');
  expect(input).toHaveValue('Hello world.');

  input.setSelectionRange(6, 6);
  await userEvent.click(splitButton);
  expect(firstRow.queryByRole('alert')).not.toBeInTheDocument();
  expect(input).toHaveValue('Hello');
  expect(screen.getByRole('textbox', { name: 'Sentence 2' })).toHaveValue('world.');
  expect(firstRow.getByRole('button', { name: 'Seek to sentence' })).toBeEnabled();
  expect(api.saveChanges).not.toHaveBeenCalled();
  await userEvent.click(mergeButton);
  expect(input).toHaveValue('Hello world.');
  expect(screen.queryByRole('textbox', { name: 'Sentence 2' })).not.toBeInTheDocument();
  expect(mergeButton).toBeDisabled();
});
it('sends the new sentence with a boolean cloze flag before running processing', async () => {
  const { saveChanges } = await vi.importActual<typeof api>('./lesson-builder.api');
  vi.mocked(api.saveChanges).mockImplementationOnce(saveChanges);
  const saved = { ...original, revision: 2 };
  vi.mocked(request).mockResolvedValueOnce(saved);
  await show({ ...original, sentences: [{ ...original.sentences[0], clozeManual: true }] });
  const input = screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Sentence 1' });
  input.setSelectionRange(6, 6);
  await userEvent.click(screen.getByRole('button', { name: 'Split at cursor' }));
  await userEvent.click(screen.getByRole('button', { name: 'Update affected parts' }));
  await userEvent.click(screen.getByRole('button', { name: 'Start processing' }));

  expect(request).toHaveBeenCalledWith('/api/v1/admin/lesson-authoring/lesson-a/changes', expect.objectContaining({ method: 'PUT' }));
  const payload = JSON.parse(vi.mocked(request).mock.calls[0][1]!.body as string);
  expect(payload.changes.sentences).toMatchObject([
    { id: 's1', text: 'Hello', clozeManual: true },
    { id: null, text: 'world.', clozeManual: false },
  ]);
  await waitFor(() => expect(api.runLesson).toHaveBeenCalledWith('lesson-a', saved.revision));
});
it('retains local input on save conflict and background refetch',async()=>{vi.mocked(api.saveChanges).mockRejectedValue(new Error('Revision conflict'));const client=await show();fireEvent.change(screen.getByRole('textbox',{name:'Translation'}),{target:{value:'Keep this edit'}});fireEvent.keyDown(document,{key:'s',metaKey:true});await screen.findByText('Revision conflict');client.setQueryData(['authoring','lesson-a'],{...original,revision:9});await waitFor(()=>expect(screen.getByRole('textbox',{name:'Translation'})).toHaveValue('Keep this edit'));});
it('saves before explicit update and states which steps will run',async()=>{await show({...structuredClone(original),alignmentRequired:true,contentComplete:false,contentNeeded:true});fireEvent.change(screen.getByRole('textbox',{name:'Sentence 1'}),{target:{value:'Hello again.'}});fireEvent.click(screen.getByRole('button',{name:'Update affected parts'}));expect(await screen.findByText(/Will run: alignment/)).toBeInTheDocument();expect(api.runLesson).not.toHaveBeenCalled();fireEvent.click(screen.getByRole('button',{name:'Start processing'}));await waitFor(()=>expect(api.runLesson).toHaveBeenCalledWith('lesson-a',2));});
it('preview shows real blockers and does not require confirmation checkboxes',async()=>{vi.mocked(api.fetchReadiness).mockResolvedValue({ready:false,blockers:['IPA_MISSING:1']});await show();fireEvent.click(screen.getByRole('button',{name:'Preview & publish'}));expect(await screen.findByText(/IPA missing: edit manually/)).toBeInTheDocument();expect(screen.getByRole('button',{name:'Publish lesson'})).toBeDisabled();expect(screen.queryByRole('checkbox',{name:/checked/})).not.toBeInTheDocument();});
it('publishes the current revision from a ready preview',async()=>{vi.mocked(api.publishLesson).mockResolvedValue({...original,status:'PUBLISHED',revision:2});await show();fireEvent.click(screen.getByRole('button',{name:'Preview & publish'}));fireEvent.click(await screen.findByRole('button',{name:'Publish lesson'}));await waitFor(()=>expect(api.publishLesson).toHaveBeenCalledWith('lesson-a',1));});
it('shows published lessons without another publish action or readiness request',async()=>{
  await show({...structuredClone(original),status:'PUBLISHED'});
  expect(screen.queryByRole('button',{name:'Preview & publish'})).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button',{name:'Preview'}));
  await screen.findByRole('dialog');
  expect(screen.queryByRole('button',{name:'Publish lesson'})).not.toBeInTheDocument();
  expect(api.fetchReadiness).not.toHaveBeenCalled();
  expect(api.publishLesson).not.toHaveBeenCalled();
});
it('shows the backend failure immediately in history and renders output as text',async()=>{const job={jobId:'job1',operation:'CONTENT',status:'FAILED',errorCode:'CONTENT_PARTIAL',errorMessage:'sentence=41: translation missing',usage:[]};vi.mocked(api.fetchDiagnostics).mockResolvedValue([{payload:{output:'<script>alert(1)</script>'}}]);const {container}=await (async()=>{await show({...original,jobs:[job]});return {container:document.body};})();fireEvent.click(screen.getByRole('button',{name:/Jobs for this lesson/}));expect(await screen.findByText(/CONTENT_PARTIAL: sentence=41/)).toBeInTheDocument();fireEvent.click(screen.getByRole('button',{name:'Debug'}));await screen.findByText(/<script>alert/);expect(container.querySelector('script')).toBeNull();});
it('returns to a clean state when an edit is reverted',async()=>{
  await show();const text=screen.getByRole('textbox',{name:'Sentence 1'});
  fireEvent.change(text,{target:{value:'Changed text.'}});
  expect(screen.getByRole('button',{name:'Update affected parts'})).toBeEnabled();
  fireEvent.change(text,{target:{value:'Hello world.'}});
  expect(screen.getByRole('button',{name:'Update affected parts'})).toBeDisabled();
  fireEvent.keyDown(document,{key:'s',ctrlKey:true});
  expect(api.saveChanges).not.toHaveBeenCalled();
});

it('shows a live chain stage and disables mutations while alignment is running',async()=>{
  await show({...structuredClone(original),alignmentRequired:true,contentComplete:false,contentNeeded:true,jobs:[{jobId:'aligning',operation:'ALIGN',currentStep:'ALIGNING',status:'RUNNING',progressPercent:0,createdAt:new Date().toISOString(),usage:[]}]});
  expect(screen.getByRole('region',{name:'Lesson progress'})).toHaveTextContent('Align word timing');
  expect(screen.getByRole('progressbar')).toBeInTheDocument();
  expect(screen.getByRole('textbox',{name:'Sentence 1'})).toBeDisabled();
  expect(screen.getByRole('button',{name:'Lesson settings'})).toBeDisabled();
  expect(screen.getByRole('button',{name:'Preview & publish'})).toBeDisabled();
  expect(screen.getByRole('button',{name:'Request cancellation'})).toBeEnabled();
  expect(api.runLesson).not.toHaveBeenCalled();
});
it('keeps failed-step recovery visible alongside the saved transcript',async()=>{
  await show({...structuredClone(original),contentComplete:false,contentNeeded:true,jobs:[{jobId:'broken',operation:'CONTENT',status:'FAILED',errorCode:'CONTENT_PARTIAL',errorMessage:'sentence=41: missing translation',usage:[]}]});
  expect(screen.getByText('CONTENT_PARTIAL: sentence=41: missing translation')).toBeVisible();
  expect(screen.getByRole('button',{name:'Retry missing content'})).toBeEnabled();
  expect(screen.getByRole('button',{name:/Jobs for this lesson/})).toBeEnabled();
  expect(screen.getByRole('textbox',{name:'Sentence 1'})).toBeEnabled();
});

it('starts the transport from the first playable sentence before a row is clicked',async()=>{
  await show();expect(screen.getByTestId('player')).toHaveAttribute('data-selected','s1');
});

it('collects vocabulary and grammar across sentences and jumps to the source note', async () => {
  const first = { ...original.sentences[0], vocabulary: [
    { surface: 'Hello', explanation: 'A greeting', label: 'LEXICAL', importance: 'MEDIUM', tokenStart: 0, tokenEnd: 1 },
    { surface: '', explanation: '', label: 'LEXICAL', importance: 'MEDIUM', tokenStart: -1, tokenEnd: -1 },
  ] };
  const second = { ...original.sentences[0], id: 's2', contentStale: true, grammar: [
    { surface: 'Hello world', explanation: 'A greeting followed by an address', label: 'GRAMMAR', importance: 'MEDIUM', tokenStart: 0, tokenEnd: 2 },
  ] };
  await show({ ...original, sentences: [first, second] });
  await userEvent.click(screen.getByRole('button', { name: 'Lesson notes 2' }));
  const dialog = screen.getByRole('dialog', { name: 'Lesson notes' });
  expect(within(dialog).getByText('A greeting')).toBeVisible();
  expect(within(dialog).getByText('A greeting followed by an address')).toBeVisible();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Open sentence 2' }));
  await waitFor(() => expect(screen.getByTestId('player')).toHaveAttribute('data-selected', 's2'));
});

it('shows publication failures inside the open preview and allows retry',async()=>{
  vi.mocked(api.publishLesson).mockRejectedValue(new Error('Publication failed'));
  await show();fireEvent.click(screen.getByRole('button',{name:'Preview & publish'}));
  fireEvent.click(await screen.findByRole('button',{name:'Publish lesson'}));
  const dialog=screen.getByRole('dialog');
  await waitFor(()=>expect(within(dialog).getByRole('alert')).toHaveTextContent('Publication failed'));
  expect(within(dialog).getByRole('button',{name:'Publish lesson'})).toBeEnabled();
});
it('discards transcript, selection and metadata edits without saving or reloading',async()=>{
  await show();
  expect(screen.queryByRole('button',{name:'Discard changes'})).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('checkbox',{name:'Shadowing'}));
  fireEvent.change(screen.getByRole('textbox',{name:'Sentence 1'}),{target:{value:'Unsaved transcript.'}});
  await userEvent.click(screen.getByRole('button',{name:'Lesson settings'}));
  fireEvent.change(screen.getByRole('textbox',{name:'Title'}),{target:{value:'Unsaved title'}});
  await userEvent.click(screen.getByRole('button',{name:'Close'}));
  const dirty=new Event('beforeunload',{cancelable:true});window.dispatchEvent(dirty);
  expect(dirty.defaultPrevented).toBe(true);
  await userEvent.click(screen.getByRole('button',{name:'Discard changes'}));
  expect(screen.getByRole('textbox',{name:'Sentence 1'})).toHaveValue('Hello world.');
  expect(screen.getByRole('checkbox',{name:'Shadowing'})).toBeChecked();
  expect(screen.getByRole('heading',{level:1})).toHaveTextContent('A lesson');
  expect(screen.queryByRole('button',{name:'Discard changes'})).not.toBeInTheDocument();
  const clean=new Event('beforeunload',{cancelable:true});window.dispatchEvent(clean);
  expect(clean.defaultPrevented).toBe(false);
  await userEvent.click(screen.getByRole('button',{name:'Lesson settings'}));
  expect(screen.getByRole('textbox',{name:'Title'})).toHaveValue('A lesson');
  expect(api.fetchDraft).toHaveBeenCalledOnce();
  expect(catalog.fetchMetadata).toHaveBeenCalledOnce();
  expect(catalog.saveMetadata).not.toHaveBeenCalled();
  expect(api.saveChanges).not.toHaveBeenCalled();
  expect(api.runLesson).not.toHaveBeenCalled();
});
it('disables discarding while the lesson is being processed',async()=>{
  const client=await show();
  fireEvent.change(screen.getByRole('textbox',{name:'Sentence 1'}),{target:{value:'Unsaved transcript.'}});
  client.setQueryData(['authoring','lesson-a'],{...original,jobs:[{jobId:'running',operation:'ALIGN',status:'RUNNING',usage:[]}]});
  await waitFor(()=>expect(screen.getByRole('button',{name:'Discard changes'})).toBeDisabled());
  expect(screen.getByRole('textbox',{name:'Sentence 1'})).toHaveValue('Unsaved transcript.');
  client.clear();
});
