import { useTranslation } from "react-i18next";
import { QualityPanel } from "../library/QualityPanel";
export function GrammarReview() {
  const { t } = useTranslation("admin");
  return <div className="grid gap-4"><div className="flex flex-wrap items-start justify-between gap-3"><div><h2 className="font-semibold">{t("grammarReview.title")}</h2><p className="mt-1 text-sm text-muted-foreground">{t("grammarReview.help")}</p></div></div><QualityPanel endpoint="/api/v1/admin/grammar/quality" /></div>;
}
export function GrammarCheck({kind,id,dirty,revision}: {kind:string;id:string;dirty:boolean;revision:number}) {
  return id !== "new" && <QualityPanel endpoint={`/api/v1/admin/grammar/${kind}/${id}/quality`} dirty={dirty} revision={revision} />;
}
