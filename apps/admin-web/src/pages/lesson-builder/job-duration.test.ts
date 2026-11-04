import { expect, it } from 'vitest';
import { jobDuration } from './AuthoringJobProgress';
import type { AuthoringJob } from './lesson-builder.types';
const job = (patch: Partial<AuthoringJob>): AuthoringJob => ({jobId:'a',operation:'ALIGN',status:'RUNNING',usage:[],createdAt:'2026-11-03T00:00:00Z',startedAt:'2026-11-03T00:00:05Z',...patch});
it('uses persisted start after remount, retains retry time and freezes terminal duration', () => {
  const now=Date.parse('2026-11-03T00:01:05Z');
  expect(jobDuration(job({}),now)).toEqual({ms:60000,waiting:false});
  expect(jobDuration(job({status:'RETRY_WAIT'}),now)).toEqual({ms:60000,waiting:false});
  expect(jobDuration(job({status:'FAILED',finishedAt:'2026-11-03T00:00:28Z'}),now)).toEqual({ms:23000,waiting:false});
  expect(jobDuration(job({status:'CANCELLED',startedAt:null,finishedAt:'2026-11-03T00:00:08Z'}),now)).toEqual({ms:8000,waiting:true});
});
it('does not invent timestamps and bounds client clock skew', () => {
  expect(jobDuration(job({createdAt:null,startedAt:null}),1000)).toBeNull();
  expect(jobDuration(job({status:'SUCCEEDED',finishedAt:null}),1000)).toBeNull();
  expect(jobDuration(job({}),0)).toEqual({ms:0,waiting:false});
});
