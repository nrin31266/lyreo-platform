import { useState } from "react";
import { useTranslation } from "react-i18next";
import { RiImageLine as ImageOff, RiRefreshLine as RefreshCw, RiErrorWarningLine as AlertCircle } from "@remixicon/react";
import { Alert, AlertTitle, AlertDescription } from "@/components/ui/alert";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api";
export function CatalogError({
  error,
  retry,
  retrying = false,
  title,
}: {
  error: unknown;
  retry?: () => void;
  retrying?: boolean;
  title?: string;
}) {
  const { t } = useTranslation("admin");
  if (!error) return null;
  const status = error instanceof ApiError ? error.status : undefined;
  const help = status === 0 ? "connectionFailed"
    : status === 401 ? "sessionExpired"
    : status === 403 ? "accessDenied"
    : status === 429 ? "tooManyRequests"
    : status !== undefined && status >= 500 ? "serviceUnavailable"
    : "requestFailedHelp";
  // Domain validation details are safe and actionable; internal failures are not.
  const detail = error instanceof ApiError && [400, 409, 422].includes(error.status)
    ? error.detail : undefined;
  return (
    <Alert className="flex flex-wrap items-center gap-4 p-5">
      <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-destructive/10 text-destructive">
        <AlertCircle className="size-5" aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1 basis-64 space-y-1">
        <AlertTitle>{title ?? t("catalog.requestFailed")}</AlertTitle>
        <AlertDescription className="break-words">
          <p>{t(`catalog.${help}`)}</p>
          {detail && <p>{detail}</p>}
          {error instanceof ApiError && error.correlationId && (
            <p className="mt-2 font-mono text-xs">
              {t("catalog.errorReference", { id: error.correlationId })}
            </p>
          )}
        </AlertDescription>
      </div>
      {retry && status !== 401 && status !== 403 && (
        <Button type="button" variant="outline" size="sm" onClick={retry} disabled={retrying}>
          <RefreshCw data-icon="inline-start" aria-hidden="true" className={retrying ? "motion-safe:animate-spin" : undefined} />
          {t(retrying ? "catalog.retrying" : "catalog.retry")}
        </Button>
      )}
    </Alert>
  );
}
export function CatalogSkeleton() {
  const { t } = useTranslation("admin");
  return (
    <div role="status" aria-label={t("loading")} className="grid gap-3">
      {Array.from({ length: 5 }, (_, i) => (
        <div key={i} className="flex items-center gap-4 rounded-lg border p-4">
          <Skeleton className="h-14 w-24 shrink-0" />
          <div className="grid flex-1 gap-2">
            <Skeleton className="h-4 w-2/3" />
            <Skeleton className="h-3 w-1/3" />
          </div>
        </div>
      ))}
    </div>
  );
}
function RemoteImage({
  url,
  title,
  avatar,
}: {
  url: string;
  title: string;
  avatar: boolean;
}) {
  const [failed, setFailed] = useState(false);
  return failed ? (
    <ImageOff
      aria-hidden="true"
      className="m-auto size-5 text-muted-foreground"
    />
  ) : (
    <img
      src={url}
      alt={title}
      loading="lazy"
      referrerPolicy="no-referrer"
      className={avatar ? "size-full object-cover" : "size-full object-cover"}
      onError={() => setFailed(true)}
    />
  );
}
export function CatalogImage({
  url,
  title,
  avatar = false,
}: {
  url?: string | null;
  title: string;
  avatar?: boolean;
}) {
  return (
    <span
      className={
        avatar
          ? "flex size-10 shrink-0 overflow-hidden rounded-full bg-muted"
          : "flex h-14 w-24 shrink-0 overflow-hidden rounded-md bg-muted"
      }
    >
      {url ? (
        <RemoteImage key={url} url={url} title={title} avatar={avatar} />
      ) : (
        <ImageOff
          aria-hidden="true"
          className="m-auto size-5 text-muted-foreground"
        />
      )}
    </span>
  );
}
