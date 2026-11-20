import { Fragment, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { RiEditLine } from "@remixicon/react";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { CatalogError, CatalogSkeleton } from "@/pages/lessons/CatalogFeedback";
import { MediaPreview } from "../library/ContentMedia";
import { LibrarySelect } from "../library/LibrarySelect";
import { PassagePreview } from "../library/PassageEditor";
import type { Media, Placement, Question, ToeicDraft } from "../library/library.types";

type Preview = { test: ToeicDraft; questions: Record<string, Question> };
const parts = [1, 2, 3, 4, 5, 6, 7];

function PreviewMedia({ media }: { media: Media[] }) {
  const { t } = useTranslation("admin");
  const [open, setOpen] = useState(false);
  if (!media.length) return null;
  return <details onToggle={event => setOpen(event.currentTarget.open)}>
    <summary className="cursor-pointer text-sm font-medium">{t("toeicPreview.media", { count: media.length })}</summary>
    {open && <div className="mt-3 grid gap-3">{media.map(asset =>
      <MediaPreview key={`${asset.assetId}-${asset.role}`} {...asset} />
    )}</div>}
  </details>;
}

export function ToeicPreview({ testId, revision, onEdit }: {
  testId: string;
  revision: number;
  onEdit: (placement: Placement) => void;
}) {
  const { t } = useTranslation("admin");
  const [scope, setScope] = useState("all");
  const query = useQuery({
    queryKey: ["toeic-overview", testId, revision],
    queryFn: ({ signal }) => api<Preview>(`/api/v1/admin/toeic/${testId}/preview`, { signal }),
  });
  const selectedParts = parts.filter(part => scope === "all" || scope === "listening" && part <= 4
    || scope === "reading" && part >= 5 || scope === String(part));
  const preview = query.data;
  const groups = new Map(preview?.test.groups.map(group => [group.id, group]));
  const placements = (preview?.test.placements ?? []).filter(row => selectedParts.includes(row.part));
  return <div className="grid min-w-0 max-w-5xl gap-5">
    <div className="flex flex-wrap items-end gap-x-5 gap-y-2">
      <div className="w-full max-w-56">
        <LibrarySelect label={t("toeicPreview.scope")} value={scope} onValueChange={setScope} options={[
          ...["all", "listening", "reading"].map(value => ({ value, label: t(`toeicPreview.${value}`) })),
          ...parts.map(number => ({ value: String(number), label: t("library.part", { number }) })),
        ]} />
      </div>
      {preview && <p className="pb-2 text-sm text-muted-foreground">{t("library.questionCount", { count: placements.length })}</p>}
    </div>
    <CatalogError error={query.error} retry={() => void query.refetch()} />
    {query.isPending && <CatalogSkeleton />}
    {preview && placements.length === 0 && <p className="text-sm text-muted-foreground">{t("toeicPreview.empty")}</p>}
    {preview && placements.length > 0 && selectedParts.map(part => {
      const rows = placements.filter(row => row.part === part).sort((a, b) => a.questionNumber - b.questionNumber);
      if (!rows.length) return null;
      const firstInGroup = new Set<string>();
      return <section key={part} className="grid min-w-0 gap-3">
        <h2 className="border-b pb-2 text-base font-semibold">{t("library.part", { number: part })}</h2>
        <ol className="grid gap-3">
          {rows.map(row => {
            const group = row.groupId ? groups.get(row.groupId) : undefined;
            const showGroup = group && !firstInGroup.has(group.id);
            if (group) firstInGroup.add(group.id);
            const question = preview.questions[row.id];
            const media = question?.media.filter(asset => !group?.media.some(shared => shared.assetId === asset.assetId && shared.role === asset.role));
            return <li key={row.id} className="grid gap-3 border-b pb-3">
              {showGroup && <section className="grid min-w-0 gap-3 rounded-md border bg-muted/25 p-3">
                <h3 className="text-sm font-semibold">
                  {t("library.groupQuestions", { numbers: rows.filter(placement => placement.groupId === group.id).map(placement => placement.questionNumber).join(", ") })}
                </h3>
                {group.documents.slice().sort((a, b) => a.ordinal - b.ordinal).map(document =>
                  <PassagePreview key={document.id} html={document.html} />
                )}
                <PreviewMedia media={group.media} />
                {group.transcriptEn?.trim() && <details>
                  <summary className="cursor-pointer text-sm font-medium">{t("library.transcript")}</summary>
                  <p lang="en" className="mt-2 whitespace-pre-wrap text-sm">{group.transcriptEn}</p>
                </details>}
              </section>}
              <div className="grid min-w-0 grid-cols-[auto_minmax(0,1fr)_auto] items-start gap-3">
                <span className="min-w-8 text-sm font-semibold tabular-nums">{row.questionNumber}</span>
                <div className="grid min-w-0 gap-2 text-sm">
                  {question ? <Fragment>
                    {question.title && <p lang="en" className="whitespace-pre-wrap font-medium">{question.title}</p>}
                    <ul lang="en" className="grid gap-1">
                      {question.options.map(option => <li key={option.key} className="flex gap-2">
                        <span className="font-medium">{option.key}.</span><span>{option.text}</span>
                      </li>)}
                    </ul>
                    <PreviewMedia media={media ?? []} />
                    {question.transcriptEn?.trim() && <details>
                      <summary className="cursor-pointer font-medium">{t("library.transcript")}</summary>
                      <p lang="en" className="mt-2 whitespace-pre-wrap">{question.transcriptEn}</p>
                    </details>}
                  </Fragment> : <p className="text-muted-foreground">{t("toeicPreview.questionUnavailable")}</p>}
                </div>
                <Button size="icon-sm" variant="ghost"
                  aria-label={t("toeicPreview.openQuestion", { number: row.questionNumber })}
                  tooltip={t("toeicPreview.openQuestion", { number: row.questionNumber })}
                  onClick={() => onEdit(row)}><RiEditLine aria-hidden="true" /></Button>
              </div>
            </li>;
          })}
        </ol>
      </section>;
    })}
  </div>;
}
