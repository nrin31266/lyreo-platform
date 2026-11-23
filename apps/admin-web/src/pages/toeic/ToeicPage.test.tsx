import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { api } from '@/lib/api';
import { ToeicDetailPage } from './ToeicPage';

vi.mock('@/lib/api',async original=>({...await original<typeof import('@/lib/api')>(),api:vi.fn()}));
vi.mock('../library/PassageEditor',()=>({PassagePreview:()=> <div>Source passage</div>,PassageEditor:()=> <textarea aria-label="Passage editor" />}));
afterEach(()=>{cleanup();vi.clearAllMocks();});
it('groups quality warnings and opens the affected Part and passage editor directly',async()=>{
  await i18n.changeLanguage('en');
  const cache=new QueryClient({defaultOptions:{queries:{retry:false}}});
  const question={title:'Read the passage',kind:'MCQ_SINGLE',options:[{key:'A',text:'One'},{key:'B',text:'Two'}],correctOption:'A',difficultyLevel:1,explanationVi:null,transcriptEn:null,media:[]};
  const draft={title:'Imported test',code:'SOURCE-1',type:'FULL_TEST',accessMode:'PUBLIC',year:2026,testNumber:1,groups:[{id:'group',part:7,kind:'TEXT',title:'Article and e-mail',documents:[{id:'document',ordinal:1,type:'PASSAGE',html:'<p>Source passage</p>'}],media:[],reviewed:false}],placements:[{id:'placement',itemId:'question',publicationId:null,part:7,groupId:'group',questionNumber:176,gapNumber:null}]};
  vi.mocked(api).mockImplementation(async url=>url.endsWith('/quality') ? {report:{status:'BLOCKED',issues:[{code:'DOCUMENT_REVIEW',severity:'ERROR',location:'group',message:'Review source'},{code:'PART_DISTRIBUTION',severity:'ERROR',location:'Test',message:'Incomplete test'},{code:'MISSING_EXPLANATION',severity:'WARNING',location:'Question 176',message:'Explanation is missing'}]}} : url.includes('assessment-items') ? url.endsWith('/preview') ? question : {id:'question',revision:0,draft:question} : {id:'test',revision:0,status:'PUBLISHED',publicationId:null,draft});
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={['/toeic/test?tab=quality']}><Routes><Route path="/toeic/:id" element={<ToeicDetailPage />} /></Routes></MemoryRouter></I18nextProvider></QueryClientProvider>);

  expect(await screen.findByText('Blocking issues')).toBeVisible();
  expect(screen.getByText('group · DOCUMENT_REVIEW')).toBeVisible();
  await userEvent.click(screen.getAllByRole('button',{name:'Open location'})[0]);
  expect(screen.getByRole('button',{name:/Part 7/})).toHaveAttribute('aria-pressed','true');
  expect(screen.getByRole('textbox',{name:'Passage editor'})).toBeVisible();
  expect(screen.queryByRole('button',{name:'Create drill from this Part'})).not.toBeInTheDocument();
  expect(screen.queryByRole('tab',{name:'Learner preview'})).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button',{name:/Part 1/}));
  expect(screen.getByText('Part 1 has no questions')).toBeVisible();
  cache.clear();
});

it('shows a failed quality job without displaying a previous pass',async()=>{
  await i18n.changeLanguage('en');
  const cache=new QueryClient({defaultOptions:{queries:{retry:false}}});
  const draft={title:'Test',code:'TEST',type:'FULL_TEST',accessMode:'PUBLIC',groups:[],placements:[]};
  vi.mocked(api).mockImplementation(async url=>url.endsWith('/quality')?{report:{status:'FAILED',issues:[]}}:{id:'test',revision:1,status:'PUBLISHED',publicationId:null,hasUnpublishedChanges:false,draft});
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={['/toeic/test?tab=quality']}><Routes><Route path="/toeic/:id" element={<ToeicDetailPage />} /></Routes></MemoryRouter></I18nextProvider></QueryClientProvider>);
  expect(await screen.findByText('Check failed')).toBeVisible();
  expect(screen.queryByText('Passed automated checks')).not.toBeInTheDocument();
  expect(screen.getByRole('button',{name:'Run checks'})).toBeEnabled();cache.clear();
});

it('previews the whole test with one request and filters to a Part with its shared passage', async () => {
  await i18n.changeLanguage('en');
  const cache = new QueryClient({defaultOptions:{queries:{retry:false}}});
  const question = (title: string) => ({ title, options:[{key:'A',text:'Option text'}], media:[] });
  const draft = {
    title:'Imported test',code:'TEST',type:'FULL_TEST',accessMode:'PUBLIC',
    groups:[{id:'group',part:7,kind:'TEXT',documents:[{id:'doc',ordinal:1,type:'PASSAGE',html:'<p>Source passage</p>'}],media:[]}],
    placements:[
      {id:'p192',itemId:'q192',part:7,groupId:'group',questionNumber:192},
      {id:'p1',itemId:'q1',part:1,groupId:null,questionNumber:1},
      {id:'p191',itemId:'q191',part:7,groupId:'group',questionNumber:191},
    ],
  };
  vi.mocked(api).mockImplementation(async url => url.endsWith('/test/preview')
    ? {test:draft,questions:{p1:question('Listening stem'),p191:question('First reading stem'),p192:question('Second reading stem')}}
    : url.includes('assessment-items') ? null : {id:'test',revision:0,status:'PUBLISHED',draft});
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={['/toeic/test?tab=preview']}><Routes><Route path="/toeic/:id" element={<ToeicDetailPage />} /></Routes></MemoryRouter></I18nextProvider></QueryClientProvider>);
  expect(await screen.findByText('Listening stem')).toBeVisible();
  expect(screen.getAllByText('Source passage')).toHaveLength(1);
  expect(screen.getAllByRole('button',{name:/Open question \d+ in editor/}).map(button=>button.getAttribute('aria-label')))
    .toEqual(['Open question 1 in editor','Open question 191 in editor','Open question 192 in editor']);
  await userEvent.click(screen.getByRole('combobox',{name:'Show questions'}));
  await userEvent.click(screen.getByRole('option',{name:'Reading'}));
  expect(screen.queryByText('Listening stem')).not.toBeInTheDocument();
  expect(screen.getByText('Second reading stem')).toBeVisible();
  expect(vi.mocked(api).mock.calls.map(([url])=>url)).toEqual(['/api/v1/admin/toeic/test','/api/v1/admin/toeic/test/preview']);
  await userEvent.click(screen.getByRole('button',{name:'Open question 192 in editor'}));
  expect(screen.getByRole('tab',{name:'Content'})).toHaveAttribute('aria-selected','true');
  expect(screen.getByRole('button',{name:'Question 192'})).toHaveAttribute('aria-pressed','true');
  cache.clear();
});
