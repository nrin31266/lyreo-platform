import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router-dom";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Empty, EmptyDescription } from "@/components/ui/empty";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { Input } from "@/components/ui/input";
import { cancelJob, fetchJob } from "./jobs.api";

const cancellable = new Set(["QUEUED", "RUNNING", "RETRY_WAIT"]);

export function JobsPage() {
  const { t } = useTranslation(["admin", "common"]);
  const [searchParams, setSearchParams] = useSearchParams();
  const [id, setId] = useState(searchParams.get("job") ?? "");
  const queryId = searchParams.get("job") ?? "";
  const client = useQueryClient();
  const [validationError, setValidationError] = useState("");
  const [confirmOpen, setConfirmOpen] = useState(false);
  const query = useQuery({
    queryKey: ["job", queryId],
    queryFn: () => fetchJob(queryId),
    enabled: z.uuid().safeParse(queryId).success,
    refetchInterval: (q) =>
      q.state.data &&
      ["QUEUED", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED"].includes(
        q.state.data.status,
      )
        ? 2500
        : false,
  });
  const mutation = useMutation({
    mutationFn: cancelJob,
    onSuccess: async () => {
      setConfirmOpen(false);
      await client.invalidateQueries({ queryKey: ["job", queryId] });
    },
  });
  const job = query.data ?? null;
  const loading = query.isLoading;
  const cancelling = mutation.isPending;
  const error =
    validationError || query.error?.message || mutation.error?.message;
  async function load() {
    const normalized = id.trim();
    if (!z.uuid().safeParse(normalized).success) {
      setValidationError(t("jobs.enterUuid"));
      return;
    }
    setValidationError("");
    if (normalized === queryId) await query.refetch();
    else setSearchParams({ job: normalized });
  }
  async function cancel() {
    if (job) await mutation.mutateAsync(job.id).catch(() => {});
  }

  return (
    <section>
      <div className="eyebrow">{t("jobs.eyebrow")}</div>
      <h1>{t("jobs.title")}</h1>
      <p className="lead">{t("jobs.lead")}</p>

      <Card className="mt-4">
        <CardHeader>
          <CardTitle>{t("jobs.inspect")}</CardTitle>
          <CardDescription>{t("jobs.enterUuid")}</CardDescription>
        </CardHeader>
        <CardContent>
          <FieldGroup>
            <Field orientation="responsive">
              <FieldLabel htmlFor="job-id" className="sr-only">
                {t("jobs.jobUuid")}
              </FieldLabel>
              <Input
                id="job-id"
                className="flex-1"
                value={id}
                onChange={(event) => setId(event.target.value)}
                placeholder={t("jobs.jobUuid")}
                aria-label={t("jobs.jobUuid")}
                disabled={loading}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    event.preventDefault();
                    void load();
                  }
                }}
              />
              <Button disabled={loading} onClick={() => void load()}>
                {loading ? t("common:status.loading") : t("jobs.inspect")}
              </Button>
            </Field>
          </FieldGroup>

          {error ? (
            <Alert variant="destructive" className="mt-3">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          ) : null}
          {loading ? (
            <div role="status" aria-label={t("common:status.loading")}>
              <Skeleton className="mt-3 h-20" />
            </div>
          ) : null}

          {!job && !error && !loading ? (
            <Empty className="mt-3">
              <EmptyDescription>{t("jobs.enterUuid")}</EmptyDescription>
            </Empty>
          ) : null}

          {job ? (
            <div className="job">
              <Badge variant="secondary" className="w-fit">
                {job.status}
              </Badge>
              <span>
                {job.currentStep ?? t("jobs.waiting")} · {job.progressPercent}%
              </span>
              <Progress
                value={job.progressPercent}
                aria-label={job.currentStep ?? t("jobs.waiting")}
              />
              <small>
                {t("jobs.attempt", {
                  current: job.attemptCount,
                  max: job.maxAttempts,
                })}
              </small>
              {cancellable.has(job.status) ? (
                <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
                  <DialogTrigger asChild>
                    <Button
                      className="w-fit"
                      variant="destructive"
                      disabled={cancelling}
                    >
                      {t("jobs.requestCancellation")}
                    </Button>
                  </DialogTrigger>
                  <DialogContent closeLabel={t("common:actions.close")}>
                    <DialogHeader>
                      <DialogTitle>{t("jobs.cancelDialog.title")}</DialogTitle>
                      <DialogDescription>
                        {t("jobs.cancelDialog.description")}
                      </DialogDescription>
                    </DialogHeader>
                    <div className="flex justify-end gap-2">
                      <Button
                        variant="outline"
                        disabled={cancelling}
                        onClick={() => setConfirmOpen(false)}
                      >
                        {t("common:actions.cancel")}
                      </Button>
                      <Button
                        variant="destructive"
                        disabled={cancelling}
                        onClick={() => void cancel()}
                      >
                        {cancelling
                          ? t("common:status.loading")
                          : t("jobs.cancelDialog.confirm")}
                      </Button>
                    </div>
                  </DialogContent>
                </Dialog>
              ) : null}
            </div>
          ) : null}
        </CardContent>
      </Card>
    </section>
  );
}
