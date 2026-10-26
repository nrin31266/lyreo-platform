import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ImageOff, RefreshCw, AlertCircle } from "lucide-react";
import { Alert, AlertTitle, AlertDescription } from "@/components/ui/alert";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
export function CatalogError({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  const { t } = useTranslation("admin");
  return error ? (
    <Alert variant="destructive">
      <AlertCircle />
      <AlertTitle>{t("catalog.requestFailed")}</AlertTitle>
      <AlertDescription className="gap-3">
        {error instanceof Error ? error.message : t("catalog.requestFailed")}
        {retry && (
          <Button variant="outline" size="sm" onClick={retry}>
            <RefreshCw data-icon="inline-start" />{t("catalog.retry")}
          </Button>
        )}
      </AlertDescription>
    </Alert>
  ) : null;
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
