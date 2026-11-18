import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { RiShieldCheckLine, RiRestartLine, RiArrowRightLine } from "@remixicon/react";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { AdminPagination } from "@/components/AdminPagination";
import { CatalogError } from "@/pages/lessons/CatalogFeedback";
import { cancelJob } from "@/pages/jobs/jobs.api";
import { LibrarySelect } from "./LibrarySelect";

export type QualityIssue = { code: string; severity: string; location: string; message: string; href: string | null };
type Report = { status: string; revision?: number; rulesVersion?: string; checkedAt?: string; jobId?: string; issues?: QualityIssue[] };
type QualityPanelProps = { endpoint: string; revision?: number; dirty?: boolean; onOpen?: (issue: QualityIssue) => void };
export function QualityPanel(props: QualityPanelProps) {
  return <SavedQualityPanel key={`${props.endpoint}:${props.revision ?? 0}`} {...props} />;
}
function SavedQualityPanel({ endpoint, revision = 0, dirty = false, onOpen }: QualityPanelProps) {
  const { t, i18n } = useTranslation("admin");
  const cache = useQueryClient();
  const [filter, setFilter] = useState("ALL");
  const [page, setPage] = useState(0);
  const key = ["quality-report", endpoint, revision];
  const report = useQuery({ queryKey: key, queryFn: ({ signal }) => api<{ report: Report }>(endpoint, { signal }),
    refetchInterval: query => query.state.data?.report?.status === "RUNNING" ? 2000 : false });
  const refresh = () => cache.invalidateQueries({ queryKey: ["quality-report", endpoint] });
  const run = useMutation({ mutationFn: () => api<Report>(endpoint, { method: "POST" }), onSuccess: result => { setPage(0); cache.setQueryData(key,{report:result}); void refresh(); } });
  const cancel = useMutation({ mutationFn: () => cancelJob(report.data!.report.jobId!), onSuccess: refresh });
  const current = report.data?.report;
  const status = dirty ? "STALE" : report.isError || run.isError ? "FAILED" : run.isPending ? "RUNNING" : current?.status ?? "UNCHECKED";
  const issues = dirty || ["STALE", "FAILED", "RUNNING"].includes(status) ? [] : (current?.issues ?? []);
  const filtered = issues.filter(issue => filter === "ALL" || issue.severity === filter);
  const activePage = Math.min(page, Math.max(0, Math.ceil(filtered.length / 30) - 1));
  return <section className="grid gap-4" aria-label={t("library.quality")}>
    <div className="flex flex-wrap items-center justify-between gap-3"><div className="flex flex-wrap items-center gap-3"><Badge variant="outline"><RiShieldCheckLine className="mr-1 size-4" aria-hidden="true" />{t(`quality.status.${status}`)}</Badge>{current?.checkedAt && ["PASSED", "WARNING", "BLOCKED"].includes(status) && <span className="text-xs text-muted-foreground">{new Date(current.checkedAt).toLocaleString(i18n.language)} · {t("library.revision", { revision: current.revision })} · {current.rulesVersion}</span>}</div><div className="flex gap-2">{status === "RUNNING" && !run.isPending && current?.jobId && <Button size="sm" variant="outline" disabled={cancel.isPending} onClick={() => cancel.mutate()}>{t("library.cancel")}</Button>}<Button variant="outline" size="sm" tooltip={t("quality.runHelp")} disabled={dirty || status === "RUNNING" || run.isPending} onClick={() => run.mutate()}><RiRestartLine aria-hidden="true" />{t("quality.run")}</Button></div></div>
    <p className="text-sm text-muted-foreground">{t(dirty ? "library.qualitySaveFirst" : "quality.scope")}</p>
    <CatalogError error={report.error || run.error || cancel.error} retry={() => void refresh()} />
    {status === "RUNNING" && <p role="status" className="text-sm">{t("quality.running")}</p>}
    {status === "FAILED" && <p role="alert" className="text-sm text-destructive">{t("quality.failed")}</p>}
    {issues.length > 0 && <><div className="flex items-center gap-3"><LibrarySelect label={t("quality.filter")} value={filter} onValueChange={value => {setFilter(value); setPage(0);}} options={[{value:"ALL",label:t("quality.all",{count:issues.length})},{value:"ERROR",label:t("library.blockingIssues",{count:issues.filter(i=>i.severity === "ERROR").length})},{value:"WARNING",label:t("library.qualityWarnings",{count:issues.filter(i=>i.severity === "WARNING").length})}]} /></div><div className="divide-y rounded-lg border">{filtered.slice(activePage * 30, (activePage + 1) * 30).map((issue, index) => <article key={`${issue.location}-${issue.code}-${index}`} className="flex items-start justify-between gap-3 p-3"><div className="min-w-0 text-sm"><p className="font-medium">{t(`library.qualityCodes.${issue.code}`, {defaultValue:t(`grammarReview.issues.${issue.code}`, {defaultValue:issue.message || issue.code})})}</p><p className="mt-1 text-xs text-muted-foreground">{issue.location.replace(/Question (\d+)/g, (_match, number: string) => t("library.questionNumber", {number}))} · {issue.code}</p></div>{(onOpen || issue.href) && <Button variant="ghost" size="icon-sm" tooltip={t("library.openIssueHelp")} aria-label={t("library.openIssue")} {...(onOpen ? {onClick:()=>onOpen(issue)} : {asChild:true})}>{onOpen ? <RiArrowRightLine aria-hidden="true" /> : <Link to={issue.href!}><RiArrowRightLine aria-hidden="true" /></Link>}</Button>}</article>)}</div><AdminPagination page={activePage} pageCount={Math.ceil(filtered.length / 30)} onPageChange={setPage} /></>}
  </section>;
}
