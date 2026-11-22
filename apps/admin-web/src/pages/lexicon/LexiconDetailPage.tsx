import { RiListUnordered, RiBookOpenLine, RiFileTextLine, RiSearchLine, RiVolumeUpLine } from "@remixicon/react";
import { useState } from "react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { DraftLoader, DraftWorkspace } from "@/pages/library/LibraryWorkspace";
import type { Document, LexiconDraft } from "@/pages/library/library.types";
import { LibrarySelect } from "../library/LibrarySelect";
import { DictionaryPreview } from "./DictionaryPreview";
import { PronunciationEditor } from "./PronunciationEditor";
import { FormsEditor } from "./FormsEditor";
import { SenseEditor } from "./SenseEditor";

const endpoint = "/api/v1/admin/lexicon";

export function LexiconDetailPage() {
  const { id = "" } = useParams();
  return <DraftLoader<LexiconDraft> endpoint={endpoint} id={id}>{document =>
    <LexiconWorkspace key={id} document={document} />}
  </DraftLoader>;
}

function LexiconWorkspace({ document }: { document: Document<LexiconDraft> }) {
  const { t } = useTranslation("admin");
  const [tab, setTab] = useState("lookup");
  const [selectedSense, setSelectedSense] = useState("");
  return <DraftWorkspace endpoint={endpoint} route="/lexicon" document={document}>
      {(value, change, dirty, _revision, current, adopt) =>
        <Tabs value={tab} onValueChange={setTab}>
          <TabsList className="h-auto max-w-full flex-wrap justify-start gap-1">
            <TabsTrigger value="lookup"><RiSearchLine aria-hidden="true" />{t("dictionary.lookupPreview")}</TabsTrigger>
            <TabsTrigger value="senses"><RiBookOpenLine aria-hidden="true" />{t("dictionary.editMeanings")}</TabsTrigger>
            <TabsTrigger value="sounds"><RiVolumeUpLine aria-hidden="true" />{t("library.pronunciations")}</TabsTrigger>
            <TabsTrigger value="forms"><RiListUnordered aria-hidden="true" />{t("library.forms")}</TabsTrigger>
            <TabsTrigger value="source"><RiFileTextLine aria-hidden="true" />{t("library.source")}</TabsTrigger>
          </TabsList>
          <TabsContent value="lookup" className="mt-5">
            <DictionaryPreview entry={value} onEditSense={senseId => { setSelectedSense(senseId); setTab("senses"); }} />
          </TabsContent>
          <TabsContent value="senses" className="mt-5">
            <SenseEditor value={value} onChange={change} selectedId={selectedSense} select={setSelectedSense}
              document={current} dirty={dirty} adopt={adopt} />
          </TabsContent>
          <TabsContent value="sounds" className="mt-5">
            <PronunciationEditor value={value} onChange={change} document={current} dirty={dirty} adopt={adopt} />
          </TabsContent>
          <TabsContent value="forms" className="mt-5"><FormsEditor value={value} onChange={change} /></TabsContent>
          <TabsContent value="source" className="mt-5 grid max-w-3xl gap-4">
            <div className="max-w-64">
              <LibrarySelect label={t("library.entryType")} value={value.type} onValueChange={type => change({ ...value, type })}
                options={["WORD", "PHRASE", "PHRASAL_VERB", "IDIOM", "COLLOCATION"].map(type => ({ value: type, label: t(`library.types.${type}`) }))} />
            </div>
            <p className="whitespace-pre-wrap text-sm">{value.licenseText}</p>
            {value.sourceMetadataJson && <details className="border-t pt-3">
              <summary className="cursor-pointer text-sm font-medium">{t("library.originalContent")}</summary>
              <pre className="mt-3 max-h-80 overflow-auto whitespace-pre-wrap rounded-lg bg-muted/30 p-4 text-xs">{value.sourceMetadataJson}</pre>
            </details>}
          </TabsContent>
        </Tabs>}
    </DraftWorkspace>;
}
