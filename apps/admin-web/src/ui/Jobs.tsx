import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api';

type Job = {
  id: string;
  jobType: string;
  status: string;
  currentStep?: string;
  progressPercent: number;
  attemptCount: number;
  maxAttempts: number;
};

const cancellable = new Set(['QUEUED', 'RUNNING', 'RETRY_WAIT']);

export function Jobs() {
  const [searchParams] = useSearchParams();
  const [id, setId] = useState(searchParams.get('job') ?? '');
  const [job, setJob] = useState<Job | null>(null);
  const [error, setError] = useState('');

  async function load(jobId = id) {
    const normalized = jobId.trim();
    if (!normalized) {
      setError('Enter a Job UUID.');
      return;
    }
    try {
      setError('');
      setJob(await api<Job>(`/api/v1/jobs/${normalized}`));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }

  async function cancel() {
    if (!job) return;
    try {
      await api(`/api/v1/jobs/${job.id}/cancel`, { method: 'POST' });
      await load(job.id);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }

  useEffect(() => {
    const initial = searchParams.get('job');
    if (initial) void load(initial);
    // This effect intentionally reacts only to a URL-supplied job id.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  return (
    <section>
      <div className="eyebrow">Operations</div>
      <h1>Background Jobs</h1>
      <p className="lead">
        PostgreSQL is the durable source of truth. R2 JSON is an artifact, never workflow state.
      </p>

      <div className="card">
        <div className="row">
          <input
            value={id}
            onChange={event => setId(event.target.value)}
            placeholder="Job UUID"
          />
          <button onClick={() => void load()}>Inspect</button>
        </div>

        {error ? <p className="danger">{error}</p> : null}

        {job ? (
          <div className="job">
            <strong>{job.status}</strong>
            <span>
              {job.currentStep ?? 'waiting'} · {job.progressPercent}%
            </span>
            <progress value={job.progressPercent} max="100" />
            <small>
              Attempt {job.attemptCount}/{job.maxAttempts}
            </small>
            {cancellable.has(job.status) ? (
              <button className="dangerButton" onClick={() => void cancel()}>
                Request cancellation
              </button>
            ) : null}
          </div>
        ) : null}
      </div>
    </section>
  );
}
