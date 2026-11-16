export type Job = {
  id: string;
  jobType: string;
  status: string;
  currentStep?: string;
  progressPercent: number;
  attemptCount: number;
  maxAttempts: number;
  createdAt?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  nextRetryAt?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  ownerModule?: string;
  ownerReferenceId?: string | null;
};
export type JobPage = { items: Job[]; total: number; page: number; size: number };
