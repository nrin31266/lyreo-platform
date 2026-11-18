import { useTranslation } from 'react-i18next';
import { RiAddLine, RiDeleteBinLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { TextField } from '@/pages/library/LibraryWorkspace';
import type { GrammarTheory } from '@/pages/library/library.types';

export function GrammarTheoryEditor({ theory, onChange }: {
  theory: GrammarTheory | null | undefined;
  onChange: (theory: GrammarTheory | null) => void;
}) {
  const { t } = useTranslation('admin');
  if (!theory) return <div className="grid justify-items-start gap-3">
    <p className="text-sm text-muted-foreground">{t('grammarTheory.empty')}</p>
    <Button variant="outline" onClick={() => onChange({
      order: 1, objectiveVi: '', objectiveEn: '', ruleVi: '', ruleEn: '', examples: [{ en: '', vi: '' }],
    })}><RiAddLine aria-hidden="true" />{t('grammarTheory.add')}</Button>
  </div>;

  return <div className="grid max-w-4xl gap-5">
    <div className="flex flex-wrap items-end justify-between gap-3">
      <label className="grid gap-2 text-sm font-medium">
        {t('grammarTheory.order')}
        <Input className="w-32" type="number" min={1} max={9999} value={theory.order}
          onChange={event => onChange({ ...theory, order: Number(event.target.value) })} />
      </label>
      <Button variant="ghost" onClick={() => onChange(null)}>
        <RiDeleteBinLine aria-hidden="true" />{t('grammarTheory.remove')}
      </Button>
    </div>
    <div className="grid gap-4 sm:grid-cols-2">
      <TextField label={t('grammarTheory.objectiveVi')} value={theory.objectiveVi} onChange={objectiveVi => onChange({ ...theory, objectiveVi })} />
      <TextField label={t('grammarTheory.objectiveEn')} value={theory.objectiveEn} onChange={objectiveEn => onChange({ ...theory, objectiveEn })} />
      <TextField label={t('grammarTheory.ruleVi')} value={theory.ruleVi} onChange={ruleVi => onChange({ ...theory, ruleVi })} multiline />
      <TextField label={t('grammarTheory.ruleEn')} value={theory.ruleEn} onChange={ruleEn => onChange({ ...theory, ruleEn })} multiline />
    </div>
    <div className="grid gap-3 border-t pt-4">
      <h3 className="text-sm font-semibold">{t('library.examples')}</h3>
      {theory.examples.map((example, index) => {
        const update = (patch: Partial<typeof example>) => onChange({
          ...theory, examples: theory.examples.map((row, i) => i === index ? { ...row, ...patch } : row),
        });
        return <div key={index} className="grid items-end gap-3 rounded-lg border p-3 sm:grid-cols-[1fr_1fr_auto]">
          <TextField label={t('grammarTheory.exampleEn')} value={example.en} onChange={en => update({ en })} />
          <TextField label={t('grammarTheory.exampleVi')} value={example.vi} onChange={vi => update({ vi })} />
          <Button size="icon" variant="ghost" aria-label={t('library.remove')} tooltip={t('library.help.remove')}
            onClick={() => onChange({ ...theory, examples: theory.examples.filter((_, i) => i !== index) })}>
            <RiDeleteBinLine aria-hidden="true" />
          </Button>
        </div>;
      })}
      <Button variant="outline" className="justify-self-start" disabled={theory.examples.length >= 8}
        onClick={() => onChange({ ...theory, examples: [...theory.examples, { en: '', vi: '' }] })}>
        <RiAddLine aria-hidden="true" />{t('dictionary.addExample')}
      </Button>
      <p className="text-sm text-muted-foreground">{t('grammarTheory.practiceHelp')}</p>
    </div>
  </div>;
}
