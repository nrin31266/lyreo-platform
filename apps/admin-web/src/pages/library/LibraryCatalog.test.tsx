import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nextProvider } from 'react-i18next';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { api } from '@/lib/api';
import { LibraryList } from './LibraryCatalog';
vi.mock('@/lib/api', async original => ({ ...await original<typeof import('@/lib/api')>(), api: vi.fn() }));
let client: QueryClient;
beforeEach(async () => { vi.clearAllMocks(); await i18n.changeLanguage('en'); client=new QueryClient({defaultOptions:{queries:{retry:false}}}); });
afterEach(() => { cleanup(); client.clear(); });
function show(route: string, endpoint: string, url=route) {
  render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={[url]}><LibraryList route={route} endpoint={endpoint} title="Library" /></MemoryRouter></I18nextProvider></QueryClientProvider>);
}
it('shows the true last page, preserves filters when jumping, and uses the shared Select', async () => {
  vi.mocked(api).mockResolvedValue({items:[{id:'q',title:'Question',status:'PUBLISHED',difficultyLevel:3}],total:15803,page:0,size:30});
  show('/grammar/questions','/api/v1/admin/assessment-items','/grammar/questions?q=tense&difficulty=3');
  await userEvent.click(await screen.findByRole('button',{name:'Page 527'}));
  await waitFor(() => expect(api).toHaveBeenCalledWith(expect.stringMatching(/assessment-items\/page\?q=tense&size=30&page=526&difficulty=3/), expect.anything()));
  expect(screen.getByRole('combobox',{name:'Difficulty (1–5)'})).toBeInTheDocument();
  expect(document.querySelector('select:not([aria-hidden="true"])')).toBeNull();
});
it('returns to the real final page after filtered results shrink',async()=>{
  vi.mocked(api).mockResolvedValue({items:[],total:31,page:4,size:30});
  show('/grammar/questions','/api/v1/admin/assessment-items','/grammar/questions?page=4&q=tense');
  await waitFor(()=>expect(api).toHaveBeenCalledWith(expect.stringMatching(/page\?q=tense&size=30&page=1/),expect.anything()));
  expect(screen.getByRole('button',{name:'Page 2'})).toHaveAttribute('aria-current','page');
});
it('shows dictionary meaning/translation data and jumps to the final partial page', async () => {
  vi.mocked(api).mockImplementation(async url => url.includes('/count?') ? 1019 : [{id:'word',title:'Book',type:'WORD',status:'PUBLISHED',cursor:'ranked',partOfSpeech:'noun',senseCount:2,definition:'A bound volume.',translationVi:'quyển sách'}]);
  show('/lexicon','/api/v1/admin/lexicon','/lexicon?q=book');
  expect(await screen.findByText('A bound volume.')).toBeInTheDocument();
  expect(screen.getByText('Visible')).toBeInTheDocument();
  expect(screen.getByText('Word')).toBeInTheDocument();
  expect(screen.getByText('noun')).toBeInTheDocument();
  expect(screen.queryByText('Standard Listening & Reading test')).not.toBeInTheDocument();
  expect(screen.getByText('…')).toBeInTheDocument();
  await userEvent.click(await screen.findByRole('button',{name:'Page 34'}));
  await waitFor(() => expect(api).toHaveBeenCalledWith(expect.stringMatching(/browse\?q=book&size=29&page=33&tail=true&skip=0/),expect.anything()));
  await userEvent.click(screen.getByRole('button',{name:'Previous'}));
  await waitFor(() => expect(api).toHaveBeenCalledWith(expect.stringMatching(/browse\?q=book&size=30&page=32&tail=true&skip=29/),expect.anything()));
});
it('shows TOEIC in a table without manual test creation or default checkboxes', async () => {
  vi.mocked(api).mockResolvedValue({items:[{id:'test',title:'TOEIC 2026',type:'FULL_TEST',year:2026,status:'PUBLISHED',accessMode:'PUBLIC',questionCount:200,qualityStatus:'UNCHECKED'}],total:1,page:0,size:30});
  show('/toeic','/api/v1/admin/toeic');
  const card=(await screen.findByRole('link',{name:'TOEIC 2026'})).closest('tr')!;
  expect(within(card).getByRole('link',{name:'Not checked'})).toHaveAttribute('href','/toeic/test?tab=quality');
  expect(within(card).getByRole('link',{name:'Edit test'})).toHaveAttribute('href','/toeic/test');
  expect(screen.getByRole('table')).toBeInTheDocument();
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  expect(screen.queryByRole('link',{name:'Create test / drill'})).not.toBeInTheDocument();
});
it('uses the imported POS code when filtering adjectives', async () => {
  vi.mocked(api).mockImplementation(async url => url.includes('/count?') ? 0 : []);
  show('/lexicon','/api/v1/admin/lexicon');
  await userEvent.click(screen.getByRole('combobox',{name:'Part of speech'}));
  await userEvent.click(screen.getByRole('option',{name:'adjective'}));
  await waitFor(()=>expect(api).toHaveBeenCalledWith(expect.stringMatching(/browse\?.*pos=adj(?:&|$)/),expect.anything()));
});
it('hides a published item directly from its table row using the current revision',async()=>{
  let status='PUBLISHED';
  vi.mocked(api).mockImplementation(async(url,init)=>{
    if(url.endsWith('/test/status')) {status='HIDDEN';return {id:'test',status,revision:4};}
    if(url.endsWith('/test')) return {id:'test',status,revision:3};
    return {items:[{id:'test',title:'TOEIC 2026',status,type:'FULL_TEST',questionCount:200}],total:1,page:0,size:30};
  });
  show('/toeic','/api/v1/admin/toeic');
  await userEvent.click(await screen.findByRole('button',{name:'Hide TOEIC 2026'}));
  await waitFor(()=>expect(api).toHaveBeenCalledWith('/api/v1/admin/toeic/test/status',expect.objectContaining({method:'PUT',body:JSON.stringify({revision:3,status:'HIDDEN'})})));
  expect(await screen.findByRole('button',{name:'Show TOEIC 2026'})).toBeVisible();
  expect(api).not.toHaveBeenCalledWith(expect.stringContaining('/publish'),expect.anything());
});
it('keeps the parent topic in subtopic navigation without manual creation',async()=>{
  vi.mocked(api).mockImplementation(async url=>url.includes('/topic/page?')?{items:[{id:'topic',title:'Tenses'}],total:1,page:0,size:100}:{items:[{id:'sub',title:'Present tense',status:'PUBLISHED',parentId:'topic',questionCount:10}],total:1,page:0,size:30});
  show('/grammar/subtopic','/api/v1/admin/grammar/subtopic','/grammar/subtopic?parentId=topic');
  expect(await screen.findByRole('link',{name:'Tenses'})).toHaveAttribute('href','/grammar/topic/topic');
  expect(screen.queryByRole('link',{name:'Add subtopic'})).not.toBeInTheDocument();
  expect(api).toHaveBeenCalledWith(expect.stringContaining('parentId=topic'),expect.anything());
});

it('searches after typing stops without requiring a search button and resets pagination', async () => {
  vi.mocked(api).mockResolvedValue({items:[],total:0,page:0,size:30});
  show('/grammar/questions','/api/v1/admin/assessment-items','/grammar/questions?page=3');
  const input=screen.getByRole('textbox',{name:'Search library…'});
  await userEvent.type(input,'tense');
  expect(api).not.toHaveBeenCalledWith(expect.stringContaining('q=tense'),expect.anything());
  await waitFor(()=>expect(api).toHaveBeenCalledWith(expect.stringMatching(/page\?q=tense&size=30&page=0/),expect.anything()));
  expect(screen.queryByRole('button',{name:'Search library…'})).not.toBeInTheDocument();
});

it.each([
  ['en', ['Phrasal verb', 'Idiom', 'Collocation'], 'adjective'],
  ['vi', ['Cụm động từ', 'Thành ngữ', 'Kết hợp từ'], 'Tính từ'],
] as const)('renders entry types and parts of speech in %s without translation keys', async (language, types, adjective) => {
  await i18n.changeLanguage(language);
  vi.mocked(api).mockImplementation(async url => url.includes('/count?') ? 0 : []);
  show('/lexicon', '/api/v1/admin/lexicon');
  await userEvent.click(screen.getByRole('combobox', { name: i18n.t('admin:library.entryType') }));
  for (const type of types) expect(screen.getByRole('option', { name: type })).toBeVisible();
  expect(screen.queryByText(/library\.types\./)).not.toBeInTheDocument();
  await userEvent.keyboard('{Escape}');
  await userEvent.click(screen.getByRole('combobox', { name: i18n.t('admin:library.partOfSpeech') }));
  expect(screen.getByRole('option', { name: adjective })).toBeVisible();
});
