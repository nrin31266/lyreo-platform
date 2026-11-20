import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { api } from '@/lib/api';
import { ToeicQuestion } from './ToeicQuestion';

vi.mock('@/lib/api',async original=>({...await original<typeof import('@/lib/api')>(),api:vi.fn()}));
const late={adopt:undefined as undefined | ((doc:never)=>void)};
vi.mock('../library/AiSuggestions',()=>({AiSuggestions:({adopt}:{adopt:(doc:never)=>void})=><button onClick={()=>{late.adopt=adopt;}}>Start assistant</button>}));
let cache:QueryClient;
const question={id:'q',revision:3,status:'PUBLISHED',publicationId:'pub',hasUnpublishedChanges:false,updatedAt:null,updatedBy:null,draft:{title:'Original question',kind:'MCQ_SINGLE',options:[{key:'A',text:'One'},{key:'B',text:'Two'},{key:'C',text:'Three'},{key:'D',text:'Four'}],correctOption:'A',difficultyLevel:1,translationVi:null,explanationVi:null,answerTranslationVi:null,vocabularyNote:null,transcriptEn:null,media:[]}};
beforeEach(async()=>{vi.clearAllMocks();late.adopt=undefined;await i18n.changeLanguage('en');cache=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});vi.mocked(api).mockResolvedValue(question);});
afterEach(()=>{cleanup();cache.clear();vi.restoreAllMocks();});
async function show() {
  const onDirtyChange=vi.fn(),onSaved=vi.fn();
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter><ToeicQuestion testId="test" placement={{id:'placement',itemId:'q',publicationId:'pub',groupId:null,part:5,questionNumber:101,gapNumber:null}} revision={7} dirty={false} onSaved={onSaved} onDirtyChange={onDirtyChange} /></MemoryRouter></I18nextProvider></QueryClientProvider>);
  await userEvent.click(screen.getByRole('button',{name:'Edit question'}));
  await screen.findByDisplayValue('Original question');
  return {onDirtyChange,onSaved};
}
it('discards question edits and ignores an assistant callback from the discarded editor',async()=>{
  const {onDirtyChange}=await show();
  expect(screen.getByRole('button',{name:'Save question to test draft'})).toBeDisabled();
  await userEvent.click(screen.getByRole('button',{name:'Start assistant'}));
  const callback=late.adopt!;
  fireEvent.change(screen.getByLabelText('Question text (EN)'),{target:{value:'Unsaved question'}});
  await waitFor(()=>expect(onDirtyChange).toHaveBeenLastCalledWith(true));
  await userEvent.click(screen.getByRole('button',{name:'Discard changes'}));
  expect(screen.getByLabelText('Question text (EN)')).toHaveValue('Original question');
  callback({...question,draft:{...question.draft,title:'Stale AI response'}} as never);
  expect(screen.getByLabelText('Question text (EN)')).toHaveValue('Original question');
  expect(screen.getByRole('button',{name:'Save question to test draft'})).toBeDisabled();
  expect(screen.getByRole('tab',{name:'Images'})).toBeVisible();
  expect(screen.queryByRole('tab',{name:'Media & transcript'})).not.toBeInTheDocument();
  expect(screen.queryByRole('button',{name:/Remove option/})).not.toBeInTheDocument();
});
it('keeps local edits when closing is cancelled and saves with both contextual revisions',async()=>{
  const {onSaved}=await show();
  fireEvent.change(screen.getByLabelText('Question text (EN)'),{target:{value:'Updated question'}});
  vi.spyOn(window,'confirm').mockReturnValue(false);
  await userEvent.click(screen.getByRole('button',{name:'Edit question'}));
  expect(screen.getByLabelText('Question text (EN)')).toHaveValue('Updated question');
  vi.mocked(api).mockResolvedValue({...question,id:'test'});
  await userEvent.click(screen.getByRole('button',{name:'Save question to test draft'}));
  await waitFor(()=>expect(onSaved).toHaveBeenCalledOnce());
  const call=vi.mocked(api).mock.calls.find(([,init])=>init?.method==='PUT')!;
  expect(call[0]).toBe('/api/v1/admin/toeic/test/questions/placement');
  expect(JSON.parse(call[1]!.body as string)).toMatchObject({testRevision:7,revision:3,content:{title:'Updated question'}});
});
