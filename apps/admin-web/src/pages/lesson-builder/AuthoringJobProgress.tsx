import {
  Accordion,
  AccordionItem,
  AccordionTrigger,
  AccordionContent,
} from "@/components/ui/accordion";
import { useEffect, useState } from "react";
import { AlertCircle, Settings2 } from "lucide-react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Alert, AlertTitle, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import type { AuthoringJob } from "./lesson-builder.types";
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

export function JobProgress({
  job,
  onCancel,
}: {
  job: AuthoringJob;
  onCancel: () => void;
}) {
  const { t, i18n } = useTranslation("admin");
  const describe = useCodeLabel();
  const knownError = ["AI_CONFIGURATION_REQUIRED", "AI_FREE_TOKEN_BUDGET_EXCEEDED", "AUTHORING_VALIDATION_FAILED", "AI_OUTPUT_INCOMPLETE", "AI_HTTP_400"].includes(job.errorCode ?? "");
  const configurationError = job.errorCode === "AI_CONFIGURATION_REQUIRED" || job.errorCode === "AI_HTTP_400";
  const [observedSeconds, setObservedSeconds] = useState(0);
  useEffect(() => {
    setObservedSeconds(0);
    if (!activeJob(job)) return;
    const start = Date.now();
    const timer = setInterval(
      () => setObservedSeconds(Math.floor((Date.now() - start) / 1000)),
      1000,
    );
    return () => clearInterval(timer);
  }, [job.jobId, job.currentStep, job.status]);
  return (
    <div
      className="flex flex-col gap-2 rounded-lg border border-border p-3 text-sm"
      role="status"
    >
      <div className="flex flex-wrap items-center gap-3">
        <strong>{describe(job.operation)}</strong>
        <Badge variant={job.status === "FAILED" ? "destructive" : job.status === "SUCCEEDED" ? "success" : "secondary"}>{describe(job.status)}</Badge>
        <span>{job.currentStep ? describe(job.currentStep) : null}</span>
        {activeJob(job) ? (
          <>
            <span>
              {t("lessonAuthoring.observedTime", { seconds: observedSeconds })}
            </span>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={onCancel}
            >
              {t("lessonAuthoring.cancel")}
            </Button>
          </>
        ) : null}
        <Link to={`/jobs?job=${job.jobId}`}>
          {t("lessonAuthoring.inspectJob")}
        </Link>
      </div>
      {job.errorMessage ? (
        <Alert variant="destructive">
          <AlertCircle />
          <AlertTitle>{t("lessonAuthoring.jobFailed")}</AlertTitle>
          <AlertDescription className="grid gap-2">
            <p>{knownError ? t(`lessonAuthoring.jobErrors.${job.errorCode}`) : job.errorMessage}</p>
            {configurationError ? <Button variant="outline" size="sm" className="justify-self-start" asChild>
              <Link to="/settings/ai#routes"><Settings2 />{t("navigation.routes")}</Link>
            </Button> : <p>{t("lessonAuthoring.retryFromActions")}</p>}
          </AlertDescription>
        </Alert>
      ) : null}
      <Accordion type="single" collapsible>
        <AccordionItem value="details" className="border-0">
          <AccordionTrigger className="py-1 text-xs">
            {t("catalog.jobDetails")}
          </AccordionTrigger>
          <AccordionContent>
            {job.nextRetryAt && job.status === "RETRY_WAIT" ? (
              <p>
                {t("lessonAuthoring.retryAt", {
                  time: new Date(job.nextRetryAt).toLocaleString(i18n.language),
                })}
              </p>
            ) : null}

            {(job.usage ?? []).map((call, index) => (
              <p key={index} className="text-sm text-muted-foreground">
                {call.provider} / {call.model} ·{" "}
                {call.audioSeconds != null
                  ? t("waveform.audioUsage", {
                      seconds: call.audioSeconds.toFixed(1),
                    })
                  : null}
                {call.inputTokens != null || call.outputTokens != null
                  ? t("lessonAuthoring.usage", {
                      input: call.inputTokens ?? "—",
                      output: call.outputTokens ?? "—",
                      seconds: (call.latencyMs / 1000).toFixed(1),
                    })
                  : ` · ${(call.latencyMs / 1000).toFixed(1)} s`}
              </p>
            ))}
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </div>
  );
}
