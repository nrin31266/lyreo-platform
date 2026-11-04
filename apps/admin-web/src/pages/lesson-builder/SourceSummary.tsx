import { useTranslation } from "react-i18next";
import { timecode } from "./timecode";
import type { LessonDraft } from "./lesson-builder.types";

export function ExecutionSummary({ jobs }: Pick<LessonDraft, "jobs">) {
  const { t } = useTranslation("admin");
  const calls = (["PREPARE", "ALIGN"] as const).map(operation => ({
    operation,
    call: jobs.filter(job => job.operation === operation).flatMap(job => job.usage).at(-1),
  }));
  return <dl className="review-execution-summary">
    {calls.map(({ operation, call }) => <div key={operation}>
      <dt>{t(`reviewWorkspace.${operation === "PREPARE" ? "transcription" : "alignment"}`)}</dt>
      <dd title={call ? `${call.provider} / ${call.model}` : undefined}>
        {call ? `${call.provider} · ${call.model}` : t("reviewWorkspace.notRun")}
      </dd>
    </div>)}
  </dl>;
}

export function SourceSummary({ draft }: { draft: LessonDraft }) {
  const { t } = useTranslation("admin");
  return <section className="review-source-summary" aria-label={t("reviewWorkspace.selectedSource")}>
    <header><h2>{t("reviewWorkspace.selectedSource")}</h2><p>{t("authoringUi.sourceReview")}</p></header>
    <a href={draft.sourceReference} target="_blank" rel="noreferrer">{draft.sourceReference}</a>
    <dl className="review-range-summary">
      <div><dt>{t("waveform.start")}</dt><dd>{timecode(draft.startMs)}</dd></div>
      <div><dt>{t("waveform.end")}</dt><dd>{timecode(draft.endMs)}</dd></div>
      <div><dt>{t("sourceSelection.duration")}</dt><dd>{timecode(draft.endMs - draft.startMs)}</dd></div>
    </dl>
    <ExecutionSummary jobs={draft.jobs} />
  </section>;
}
