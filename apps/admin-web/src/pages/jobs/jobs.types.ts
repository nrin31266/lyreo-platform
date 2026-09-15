export type Job = {
  id: string;
  jobType: string;
  status: string;
  currentStep?: string;
  progressPercent: number;
  attemptCount: number;
  maxAttempts: number;
};
