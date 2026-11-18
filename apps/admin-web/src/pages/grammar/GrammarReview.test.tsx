import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { api } from '@/lib/api';
import { GrammarCheck, GrammarReview } from './GrammarReview';
vi.mock('@/lib/api', async original => ({ ...await original<typeof import('@/lib/api')>(), api: vi.fn() }));
afterEach(() => { cleanup(); vi.clearAllMocks(); });
async function renderReview(element: React.ReactNode) {
  await i18n.changeLanguage('en');
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter>{element}</MemoryRouter></I18nextProvider></QueryClientProvider>);
  return cache;
}
it('loads a persisted report without starting checks and exposes the exact question', async () => {
  vi.mocked(api).mockResolvedValue({report:{status:'WARNING',revision:3,rulesVersion:'2.0.0',issues:[{code:'MISSING_TRANSLATION',severity:'WARNING',location:'Present tense · Question 2',message:'Vietnamese translation is missing',href:'/grammar/questions/question'}]}});
  const cache=await renderReview(<GrammarReview />);
  expect(await screen.findByRole('link',{name:'Open location'})).toHaveAttribute('href','/grammar/questions/question');
  expect(api).toHaveBeenCalledWith('/api/v1/admin/grammar/quality',expect.anything());
  expect(vi.mocked(api).mock.calls.every(([,init])=>init?.method!=='POST')).toBe(true);
  cache.clear();
});
it('starts a durable check only on request and never calls an unchecked report a pass',async()=>{
  vi.mocked(api).mockImplementation(async (_url,init)=>init?.method==='POST'?{jobId:'job',status:'RUNNING'}:{report:{status:'UNCHECKED',issues:[]}});
  const cache=await renderReview(<GrammarCheck kind="bank" id="bank" revision={3} dirty={false} />);
  expect(await screen.findByText('Not checked')).toBeVisible();
  expect(screen.queryByText('Passed automated checks')).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button',{name:'Run checks'}));
  await waitFor(()=>expect(api).toHaveBeenCalledWith('/api/v1/admin/grammar/bank/bank/quality',expect.objectContaining({method:'POST'})));
  cache.clear();
});
it('marks an old report stale while the draft is unsaved and disables checking',async()=>{
  vi.mocked(api).mockResolvedValue({report:{status:'PASSED',issues:[]}});
  const cache=await renderReview(<GrammarCheck kind="bank" id="bank" revision={3} dirty />);
  expect(await screen.findByText('Check again')).toBeVisible();
  expect(screen.getByRole('button',{name:'Run checks'})).toBeDisabled();
  expect(screen.queryByText('Passed automated checks')).not.toBeInTheDocument();cache.clear();
});
it('does not retain a passing badge when refreshing the saved report fails',async()=>{
  vi.mocked(api).mockResolvedValueOnce({report:{status:'PASSED',issues:[]}}).mockRejectedValue(new Error('Offline'));
  const cache=await renderReview(<GrammarCheck kind="bank" id="bank" revision={3} dirty={false} />);
  expect(await screen.findByText('Passed automated checks')).toBeVisible();
  await cache.invalidateQueries({queryKey:['quality-report']});
  expect(await screen.findByText('Check failed')).toBeVisible();
  expect(screen.queryByText('Passed automated checks')).not.toBeInTheDocument();cache.clear();
});
it('shows the accepted running report while its refresh is still pending',async()=>{
  let reads=0;
  vi.mocked(api).mockImplementation(async (_url,init)=>{
    if(init?.method==='POST') return {jobId:'job',status:'RUNNING',issues:[]};
    if(reads++===0) return {report:{status:'PASSED',issues:[]}};
    return new Promise(()=>{});
  });
  const cache=await renderReview(<GrammarCheck kind="bank" id="bank" revision={3} dirty={false} />);
  expect(await screen.findByText('Passed automated checks')).toBeVisible();
  await userEvent.click(screen.getByRole('button',{name:'Run checks'}));
  expect(await screen.findByRole('status')).toBeVisible();
  expect(screen.queryByText('Passed automated checks')).not.toBeInTheDocument();
  expect(screen.getByRole('button',{name:'Cancel'})).toBeEnabled();cache.clear();
});
it('does not keep the old pass when starting a new check fails',async()=>{
  const checkedAt='2026-11-01T00:00:00Z';
  const timestamp=new Date(checkedAt).toLocaleString('en');
  vi.mocked(api).mockImplementation(async (_url,init)=>{
    if(init?.method==='POST') throw new Error('Unable to start check');
    return {report:{status:'PASSED',revision:3,rulesVersion:'previous-rules',checkedAt,issues:[]}};
  });
  const cache=await renderReview(<GrammarCheck kind="bank" id="bank" revision={3} dirty={false} />);
  expect(await screen.findByText('Passed automated checks')).toBeVisible();
  expect(screen.getByText(/previous-rules/)).toHaveTextContent(timestamp);
  await userEvent.click(screen.getByRole('button',{name:'Run checks'}));
  expect(await screen.findByText('Check failed')).toBeVisible();
  expect(screen.queryByText('Passed automated checks')).not.toBeInTheDocument();
  expect(screen.queryByText(/previous-rules/)).not.toBeInTheDocument();
  expect(screen.queryByText(text=>text.includes(timestamp))).not.toBeInTheDocument();cache.clear();
});
it('hides old issues and never cancels the old job while a new check is being requested',async()=>{
  let finish!: (result:unknown)=>void;
  vi.mocked(api).mockImplementation(async (_url,init)=>init?.method==='POST'?new Promise(resolve=>{finish=resolve;}):{report:{status:'WARNING',jobId:'old-job',checkedAt:'2026-11-01T00:00:00Z',revision:3,issues:[{code:'MISSING_TRANSLATION',severity:'WARNING',location:'Old issue',message:'Old warning',href:null}]}});
  const cache=await renderReview(<GrammarCheck kind="bank" id="bank" revision={3} dirty={false} />);
  expect(await screen.findByText(/Old issue/)).toBeVisible();
  await userEvent.click(screen.getByRole('button',{name:'Run checks'}));
  expect(await screen.findByRole('status')).toBeVisible();
  expect(screen.queryByText(/Old issue/)).not.toBeInTheDocument();
  expect(screen.queryByText(/Nov|11\/1\/2026/)).not.toBeInTheDocument();
  expect(screen.queryByRole('button',{name:'Cancel'})).not.toBeInTheDocument();
  await act(async()=>{finish({jobId:'new-job',status:'RUNNING',issues:[]});});
  cache.clear();
});
it('does not retain a mutation failure after switching to another saved revision',async()=>{
  vi.mocked(api).mockImplementation(async (_url,init)=>{
    if(init?.method==='POST') throw new Error('Unable to start check');
    return {report:{status:'PASSED',issues:[]}};
  });
  function Reports() {const [revision,setRevision]=useState(3);return <><button onClick={()=>setRevision(4)}>Next revision</button><GrammarCheck kind="bank" id="bank" revision={revision} dirty={false} /></>;}
  const cache=await renderReview(<Reports />);
  expect(await screen.findByText('Passed automated checks')).toBeVisible();
  await userEvent.click(screen.getByRole('button',{name:'Run checks'}));
  expect(await screen.findByText('Check failed')).toBeVisible();
  await userEvent.click(screen.getByRole('button',{name:'Next revision'}));
  expect(await screen.findByText('Passed automated checks')).toBeVisible();
  expect(screen.queryByText('Check failed')).not.toBeInTheDocument();cache.clear();
});
it('keeps the new running report when a previous GET resolves after the POST',async()=>{
  let finishOld!: (result:unknown)=>void;
  let oldSignal:AbortSignal | null | undefined;
  let reads=0;
  vi.mocked(api).mockImplementation(async (_url,init)=>{
    if(init?.method==='POST') return {jobId:'new-job',status:'RUNNING',issues:[]};
    if(reads++===0) return {report:{status:'PASSED',jobId:'old-job',issues:[]}};
    if(reads===2) {oldSignal=init?.signal;return new Promise(resolve=>{finishOld=resolve;});}
    return {report:{status:'RUNNING',jobId:'new-job',issues:[]}};
  });
  const cache=await renderReview(<GrammarCheck kind="bank" id="bank" revision={3} dirty={false} />);
  expect(await screen.findByText('Passed automated checks')).toBeVisible();
  void cache.invalidateQueries({queryKey:['quality-report']});
  await waitFor(()=>expect(reads).toBe(2));
  await userEvent.click(screen.getByRole('button',{name:'Run checks'}));
  expect(await screen.findByRole('status')).toBeVisible();
  await act(async()=>{finishOld({report:{status:'PASSED',jobId:'old-job',issues:[]}});});
  expect(oldSignal?.aborted).toBe(true);
  expect(screen.getByRole('status')).toBeVisible();
  expect(screen.queryByText('Passed automated checks')).not.toBeInTheDocument();cache.clear();
});
it('does not adopt an old POST response after changing the report endpoint and revision',async()=>{
  let finishOld!: (result:unknown)=>void;
  vi.mocked(api).mockImplementation(async (_url,init)=>init?.method==='POST'?new Promise(resolve=>{finishOld=resolve;}):{report:{status:'PASSED',issues:[]}});
  function Reports() {const [next,setNext]=useState(false);return <><button onClick={()=>setNext(true)}>Next catalog</button><GrammarCheck kind="bank" id={next?'second':'first'} revision={next?4:3} dirty={false} /></>;}
  const cache=await renderReview(<Reports />);
  expect(await screen.findByText('Passed automated checks')).toBeVisible();
  await userEvent.click(screen.getByRole('button',{name:'Run checks'}));
  expect(await screen.findByRole('status')).toBeVisible();
  await userEvent.click(screen.getByRole('button',{name:'Next catalog'}));
  expect(await screen.findByText('Passed automated checks')).toBeVisible();
  await act(async()=>{finishOld({jobId:'old-job',status:'RUNNING',issues:[]});});
  expect(screen.getByText('Passed automated checks')).toBeVisible();
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
  expect(screen.queryByRole('button',{name:'Cancel'})).not.toBeInTheDocument();cache.clear();
});
