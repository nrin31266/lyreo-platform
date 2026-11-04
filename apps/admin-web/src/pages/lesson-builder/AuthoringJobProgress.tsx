import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { RiRefreshLine, RiCloseLine, RiExternalLinkLine } from "@remixicon/react";
import { useEffect, useState } from "react";
import { RiErrorWarningLine as AlertCircle, RiEqualizerLine as Settings2 } from "@remixicon/react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import type { AuthoringJob } from "./lesson-builder.types";
export const timingErrors = ["TIMING_OUT_OF_BOUNDS", "TIMING_NON_MONOTONIC", "TIMING_COVERAGE_MISMATCH", "TIMING_ZERO_DURATION", "TIMING_OVERLAP", "TIMING_MISSING"];
const labelCodes = [
  "TRANSCRIPT_REVIEW",
  "TIMING_REVIEW",
  "CONTENT_REVIEW",
  "PUBLISHED",
  "QUEUED",
  "RUNNING",
  "RETRY_WAIT",
  "CANCEL_REQUESTED",
  "CANCELLED",
  "SUCCEEDED",
  "FAILED",
  "DRAFT_VALID",
  "VALID",
  "MISSING",
  "STALE",
  "WHISPER_DRAFT",
  "PREPARE",
  "ALIGN",
  "SEMANTIC",
  "IPA",
  "GENERATING_IPA",
  "TIMING_INVALID",
  "TIMING_COVERAGE_MISMATCH",
  "SOURCE_PREPARE",
  "TRANSCRIBING",
  "ALIGNING",
  "GENERATING_CONTENT",
  "CHECKPOINT_SAVED",
  "LONG_SENTENCE",
  "SHORT_SENTENCE",
  "TIMING_MISSING",
  "TIMING_OVERLAP",
  "TIMING_ZERO_DURATION",
  "GROUPED_TIMING",
] as const;
export function useCodeLabel() {
  const { t } = useTranslation("admin");
  return (code: string) => {
    const known = labelCodes.find((value) => value === code);
    return known ? t(`lessonAuthoring.labels.${known}`) : code;
  };
}

export const activeJob = (job: AuthoringJob) =>
  ["QUEUED", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED"].includes(job.status);
export const formatTime = (ms: number) =>
  `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, "0")}`;

export function jobDuration(job: AuthoringJob, now: number): { ms: number; waiting: boolean } | null {
  const start = Date.parse(job.startedAt ?? job.createdAt ?? '');
  const end = job.finishedAt ? Date.parse(job.finishedAt) : activeJob(job) ? now : NaN;
  return Number.isFinite(start) && Number.isFinite(end) ? { ms: Math.max(0, end - start), waiting: !job.startedAt } : null;
}
export function JobDuration({ job }: { job: AuthoringJob }) {
  const { t } = useTranslation('admin');
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    if (!activeJob(job)) return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [job.jobId, job.status]);
  const duration = jobDuration(job, now);
  return <span className="text-xs text-muted-foreground tabular-nums">{duration ? t(duration.waiting ? 'authoringFlow.waitDuration' : 'authoringFlow.jobDuration', {time: duration.ms < 60000 ? `${(duration.ms / 1000).toFixed(1)}s` : formatTime(duration.ms)}) : '—'}</span>;
}

export function authoringJobErrorKey(job: AuthoringJob) {
  return job.errorCode === "AUTHORING_VALIDATION_FAILED" && job.operation === "ALIGN"
    ? "ALIGNMENT_VALIDATION_FAILED" : job.errorCode === "AUTHORING_VALIDATION_FAILED" && job.operation === "PREPARE"
      ? "TRANSCRIPT_VALIDATION_FAILED" : job.errorCode;
}

export function JobProgress({
  job,
  onCancel,
  onRetry,
  historical = false,
}: {
  job: AuthoringJob;
  onCancel: () => void;
  onRetry?: () => void;
  historical?: boolean;
}) {
  const { t, i18n } = useTranslation("admin");
  const describe = useCodeLabel();
  const knownError = ["AI_CONFIGURATION_REQUIRED", "AI_FREE_TOKEN_BUDGET_EXCEEDED", "AUTHORING_VALIDATION_FAILED", "AI_OUTPUT_INCOMPLETE", "AI_HTTP_400", ...timingErrors].includes(job.errorCode ?? "");
  const errorLabel = authoringJobErrorKey(job);
  const configurationError = job.errorCode === "AI_CONFIGURATION_REQUIRED" || job.errorCode === "AI_HTTP_400";
  return <article className="authoring-job-card" data-historical={historical} role={historical ? undefined : "status"}>
    <header className="flex flex-wrap items-center gap-2">
      <strong className="font-medium">{describe(job.operation)}</strong>
      <Badge variant={job.status === "FAILED" ? historical ? "warning" : "destructive" : job.status === "SUCCEEDED" ? "success" : "secondary"}>{describe(job.status)}</Badge>
      {activeJob(job) && job.currentStep && <span className="text-xs text-muted-foreground">{describe(job.currentStep)}</span>}
      <JobDuration job={job} />
      <div className="authoring-job-actions ml-auto flex items-center gap-1">
        {onRetry && <Tooltip><TooltipTrigger asChild><Button type="button" size="icon-sm" variant="ghost" aria-label={t('authoringUi.retryStep')} onClick={onRetry}><RiRefreshLine /></Button></TooltipTrigger><TooltipContent>{t('authoringUi.retryStep')}</TooltipContent></Tooltip>}
        {activeJob(job) && <Tooltip><TooltipTrigger asChild><Button type="button" size="icon-sm" variant="ghost" aria-label={t('lessonAuthoring.cancel')} onClick={onCancel}><RiCloseLine /></Button></TooltipTrigger><TooltipContent>{t('lessonAuthoring.cancel')}</TooltipContent></Tooltip>}
        <Tooltip><TooltipTrigger asChild><Button size="icon-sm" variant="ghost" asChild><Link aria-label={t('lessonAuthoring.inspectJob')} to={`/jobs?job=${job.jobId}`}><RiExternalLinkLine /></Link></Button></TooltipTrigger><TooltipContent>{t('lessonAuthoring.inspectJob')}</TooltipContent></Tooltip>
      </div>
    </header>
    {job.errorMessage && <div className={historical ? "flex items-start gap-2 text-xs text-muted-foreground" : "flex items-start gap-2 text-sm text-destructive"} role={historical ? undefined : "alert"}>
      {!historical && <AlertCircle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />}
      <p>{knownError ? t(`lessonAuthoring.jobErrors.${errorLabel}`) : job.errorMessage}</p>
      {!historical && configurationError && <Button variant="outline" size="sm" asChild><Link to="/settings/ai#routes"><Settings2 />{t("navigation.routes")}</Link></Button>}
    </div>}
    {job.nextRetryAt && job.status === "RETRY_WAIT" && <p className="text-xs text-muted-foreground">{t("lessonAuthoring.retryAt", {time: new Date(job.nextRetryAt).toLocaleString(i18n.language)})}</p>}
    <details className="authoring-job-expanded"><summary>{t('authoringFlow.details')}</summary>
    <p className="text-xs text-muted-foreground">{t('authoringFlow.durationHelp')}</p>
    {job.createdAt && job.startedAt && <p className="text-xs text-muted-foreground">{t('authoringFlow.waitDuration', {time:`${(Math.max(0, Date.parse(job.startedAt)-Date.parse(job.createdAt))/1000).toFixed(1)}s`})}</p>}
    <div className="authoring-job-dates text-xs text-muted-foreground">{(['createdAt','startedAt','finishedAt'] as const).map(key => job[key] && <span key={key}>{t(`authoringFlow.${key}`)}: {new Date(job[key]!).toLocaleString(i18n.language)}</span>)}</div>
    {(job.usage ?? []).map((call, index) => <p key={index} className="authoring-job-usage">
      <span>{call.provider} / {call.model}</span>
      {call.audioSeconds != null && <span>{t("waveform.audioUsage", {seconds: call.audioSeconds.toFixed(1)})}</span>}
      <span>{call.inputTokens != null || call.outputTokens != null
        ? t("lessonAuthoring.usage", {input: call.inputTokens ?? "—", output: call.outputTokens ?? "—", seconds: (call.latencyMs / 1000).toFixed(1)})
        : `${(call.latencyMs / 1000).toFixed(1)} s`}</span>
    </p>)}
    </details>
  </article>;
}
