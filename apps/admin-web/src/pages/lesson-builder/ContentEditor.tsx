import { useTranslation } from 'react-i18next';
import { RiAddLine, RiDeleteBinLine, RiInformationLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import type { AuthoringAnnotation, AuthoringOptions, AuthoringSentence, LessonDraft } from './lesson-builder.types';

export const defaultContentOptions: AuthoringOptions = { activities: ['DICTATION', 'SHADOWING'], annotations: ['TRANSLATION', 'LEXICAL', 'GRAMMAR', 'PROFILE'], locale: 'vi-VN', density: 'AUTO', vocabularyLimit: 20, grammarLimit: 8, pronunciationStrategy: 'PREGENERATE', accent: 'US', pronunciationProvider: 'LOCAL' };
export const textTokens = (text: string) => [...text.matchAll(/[\p{L}\p{N}\p{M}]+(?:['’][\p{L}\p{N}\p{M}]+)*/gu)];
const normalized = (text: string) => text.normalize('NFKC').replace(/[‘’]/g, "'").replace(/[\s\p{Z}]+/gu, ' ').toLowerCase();
export function contentSpan(text: string, surface: string, occurrence = 1): { tokenStart: number; tokenEnd: number } | null {
  if (!surface || !Number.isInteger(occurrence) || occurrence < 1 || occurrence > 1000) return null;
  const words = textTokens(text), wanted = textTokens(surface);
  if (!wanted.length) return null;
  const phrase = normalized(surface.slice(wanted[0].index, wanted.at(-1)!.index + wanted.at(-1)![0].length));
  let seen = 0;
  for (let i = 0; i + wanted.length <= words.length; i++) {
    const end = words[i + wanted.length - 1];
    if (normalized(text.slice(words[i].index, end.index + end[0].length)) === phrase && ++seen === occurrence) return { tokenStart: i, tokenEnd: i + wanted.length };
  }
  return null;
}
export function AnnotatedTranscript({ sentence }: { sentence: AuthoringSentence }) {
  const words = textTokens(sentence.text), notes = [...sentence.vocabulary, ...sentence.grammar];
  const boundaries = new Set([0, sentence.text.length]);
  const spans = notes.flatMap(note => {
    const first = words[note.tokenStart], last = words[note.tokenEnd - 1];
    if (!first || !last || sentence.text.slice(first.index, last.index + last[0].length) !== note.surface) return [];
    const start = first.index, end = last.index + last[0].length;
    boundaries.add(start); boundaries.add(end); return [{ start, end, explanation: note.explanation }];
  });
  const sorted = [...boundaries].sort((a, b) => a - b);
  return <p className="content-transcript">{sorted.slice(0, -1).map((start, i) => {
    const end = sorted[i + 1], matching = spans.filter(span => span.start <= start && span.end >= end);
    return matching.length ? <mark key={start} title={matching.map(span => span.explanation).join('\n')}>{sentence.text.slice(start, end)}</mark> : <span key={start}>{sentence.text.slice(start, end)}</span>;
  })}</p>;
}
export function ContentOptionsPanel({ draft, disabled, onChange }: { draft: Pick<LessonDraft, "options" | "statistics">; disabled: boolean; onChange: (options: AuthoringOptions) => void }) {
  const { t } = useTranslation('admin');
  const options = draft.options ?? defaultContentOptions;
  function toggle(field: 'annotations' | 'activities', value: string, checked: boolean) { onChange({ ...options, [field]: checked ? [...new Set([...options[field], value])] : options[field].filter(item => item !== value) }); }
  const feature = (value: string) => {
    const activity = ['DICTATION', 'SHADOWING'].includes(value);
    const checked = value === 'IPA' ? options.pronunciationStrategy !== 'DISABLED' : options[activity ? 'activities' : 'annotations'].includes(value);
    return <label className="finish-check" key={value}><Checkbox checked={checked} onCheckedChange={v => value === 'IPA' ? onChange({ ...options, pronunciationStrategy: v === true ? 'PREGENERATE' : 'DISABLED' }) : toggle(activity ? 'activities' : 'annotations', value, v === true)} />{t(`contentAuthoring.${value}`)}</label>;
  };
  return <fieldset disabled={disabled} className="finish-settings">
    <section><h3>{t('finish.support')}</h3><div className="finish-fields">{feature('TRANSLATION')}{feature('IPA')}<label>{t('finish.targetLanguage')}<Input value={options.locale} maxLength={35} onChange={e => onChange({ ...options, locale: e.target.value })} /></label><label>{t('contentAuthoring.accent')}<Select value={options.accent} onValueChange={accent => onChange({ ...options, accent: accent as 'US' | 'UK' })}><SelectTrigger><SelectValue /></SelectTrigger><SelectContent><SelectItem value="US">US</SelectItem><SelectItem value="UK">UK</SelectItem></SelectContent></Select></label></div></section>
    <section><h3>{t('finish.notes')}</h3><div className="finish-fields">{feature('LEXICAL')}{feature('GRAMMAR')}<label>{t('contentAuthoring.noteBudget')}<Select value={options.density} onValueChange={density => onChange({ ...options, density: density as 'AUTO' | 'CUSTOM' })}><SelectTrigger><SelectValue /></SelectTrigger><SelectContent><SelectItem value="AUTO">{t('contentAuthoring.autoBudget')}</SelectItem><SelectItem value="CUSTOM">{t('contentAuthoring.customBudget')}</SelectItem></SelectContent></Select></label>{options.density === 'CUSTOM' && <><label>{t('contentAuthoring.vocabularyCap')}<Input type="number" min={0} max={100} value={options.vocabularyLimit} onChange={e => onChange({ ...options, vocabularyLimit: Number(e.target.value) })} /></label><label>{t('contentAuthoring.grammarCap')}<Input type="number" min={0} max={30} value={options.grammarLimit} onChange={e => onChange({ ...options, grammarLimit: Number(e.target.value) })} /></label></>}</div><p>{!draft.statistics && options.density === 'AUTO' ? t('finish.autoBudgetHelp') : t('finish.budgetHelp', { vocabulary: options.density === 'CUSTOM' ? options.vocabularyLimit : Math.ceil((draft.statistics?.wordCount ?? 0) / 100), grammar: options.density === 'CUSTOM' ? options.grammarLimit : Math.ceil((draft.statistics?.wordCount ?? 0) / 250) })}</p></section>
    <section><h3>{t('finish.activities')}</h3><div className="finish-fields">{feature('DICTATION')}{feature('SHADOWING')}</div><p>{t('finish.activitiesHelp')}</p></section>
  </fieldset>;
}
export function ContentProfile({ draft, disabled, onChange }: { draft: LessonDraft; disabled: boolean; onChange: (profile: NonNullable<LessonDraft['profile']>) => void }) {
  const { t } = useTranslation('admin');
  const profile = draft.profile ?? { cefr: 'UNKNOWN', contentType: 'UNKNOWN', manual: false, stale: true };
  return <fieldset disabled={disabled} className="finish-fields"><label>{t('contentAuthoring.cefr')}<Select value={profile.cefr} onValueChange={cefr => onChange({ ...profile, cefr: cefr as typeof profile.cefr, manual: true, stale: false })}><SelectTrigger><SelectValue /></SelectTrigger><SelectContent>{['A1', 'A2', 'B1', 'B2', 'C1', 'C2', 'UNKNOWN'].map(v => <SelectItem key={v} value={v}>{v === 'UNKNOWN' ? t('contentAuthoring.types.UNKNOWN') : v}</SelectItem>)}</SelectContent></Select><small>{t('finish.cefrHelp')}</small></label><label>{t('contentAuthoring.contentType')}<Select value={profile.contentType} onValueChange={v => onChange({ ...profile, contentType: v as typeof profile.contentType, manual: true, stale: false })}><SelectTrigger><SelectValue /></SelectTrigger><SelectContent>{['CONVERSATION', 'MONOLOGUE', 'LECTURE', 'STORY', 'NEWS', 'MIXED', 'OTHER', 'UNKNOWN'].map(v => <SelectItem key={v} value={v}>{t(`contentAuthoring.types.${v}`)}</SelectItem>)}</SelectContent></Select></label></fieldset>;
}
export function SentenceContentEditor({ sentence, options, disabled, showIpa, onChange }: { sentence: AuthoringSentence; options: AuthoringOptions; disabled: boolean; showIpa: boolean; onChange: (value: Partial<AuthoringSentence>) => void }) {
  const { t } = useTranslation('admin');
  const [vocab, grammar] = [sentence.vocabulary, sentence.grammar];
  function updateNote(kind: 'vocabulary' | 'grammar', index: number, value: Partial<AuthoringAnnotation>, occurrence?: number) {
    onChange({ [kind]: sentence[kind].map((note, i) => {
      if (i !== index) return note;
      if(!("surface" in value) && occurrence === undefined) return {...note,...value};
      const next = { ...note, ...value }, span = contentSpan(sentence.text, next.surface, occurrence);
      if (!span) return { ...next, tokenStart: -1, tokenEnd: -1 };
      const words = textTokens(sentence.text), end = words[span.tokenEnd - 1];
      return { ...next, ...span, surface: sentence.text.slice(words[span.tokenStart].index, end.index + end[0].length) };
    }) });
  }
  return <fieldset disabled={disabled} className="finish-sentence-content">
    {(options.annotations.includes('TRANSLATION') || sentence.translation) && <label className="sr-label">{t('contentAuthoring.TRANSLATION')}<Textarea aria-label={t('contentAuthoring.TRANSLATION')} rows={1} className="finish-translation" placeholder={t('finish.translationMissing')} value={sentence.translation ?? ''} onChange={e => onChange({ translation: e.target.value })} /></label>}
    {showIpa && <label className="sr-label">{t('contentAuthoring.IPA')}<Textarea aria-label={t('contentAuthoring.IPA')} rows={1} className="finish-ipa" value={sentence.ipa ?? ''} onChange={e => onChange({ ipa: e.target.value })} />{sentence.ipaNeedsReview && <small className="text-muted-foreground">{t('finish.ipaWarning')}</small>}</label>}
    <div className="finish-notes">{(['vocabulary', 'grammar'] as const).flatMap(kind => (kind === 'vocabulary' ? vocab : grammar).map((note, index) => {
      const occurrences: { tokenStart: number; tokenEnd: number }[] = [];
      for (let at = 1; at <= textTokens(sentence.text).length; at++) { const span = contentSpan(sentence.text, note.surface, at); if (!span) break; occurrences.push(span); }
      return <div className="finish-note" key={`${kind}-${index}`}><div><Input aria-label={t('contentAuthoring.phrase')} value={note.surface} onChange={e => updateNote(kind, index, { surface: e.target.value })} />{occurrences.length > 1 && <label className="finish-occurrence">{t('contentAuthoring.occurrence')}<select aria-label={t('contentAuthoring.occurrence')} value={Math.max(1, occurrences.findIndex(span => span.tokenStart === note.tokenStart) + 1)} onChange={e => updateNote(kind, index, {}, Number(e.target.value))}>{occurrences.map((_, i) => <option key={i} value={i + 1}>{i + 1}</option>)}</select></label>}</div><Textarea rows={1} maxLength={500} aria-label={t('contentAuthoring.explanation')} value={note.explanation} onChange={e => updateNote(kind, index, { explanation: e.target.value })} /><Button type="button" variant="ghost" size="icon-sm" aria-label={t('contentAuthoring.removeNote')} onClick={() => onChange({ [kind]: sentence[kind].filter((_, i) => i !== index) })}><RiDeleteBinLine /></Button>{note.tokenStart < 0 && <small role="alert">{t('contentAuthoring.invalidSpan')}</small>}</div>;
    }))}</div>
    <div className="finish-row-actions">{(['DICTATION', 'SHADOWING'] as const).filter(a => options.activities.includes(a)).map(activity => { const field = activity === 'DICTATION' ? 'dictationSelected' : 'shadowingSelected'; return <label className="finish-check" key={activity}><Checkbox checked={sentence[field]} onCheckedChange={v => onChange({ [field]: v === true })} />{t(`contentAuthoring.${activity}`)}{sentence.exclusions?.[activity] && <RiInformationLine className="size-3" aria-label={t(`finish.reasons.${sentence.exclusions[activity]}`)} />}</label>; })}<span className="finish-add-notes">{(['vocabulary', 'grammar'] as const).map(kind => <Button variant="ghost" size="sm" type="button" key={kind} onClick={() => onChange({ [kind]: [...sentence[kind], { surface: '', explanation: '', label: kind === 'vocabulary' ? 'LEXICAL' : 'GRAMMAR', importance: 'MEDIUM', tokenStart: -1, tokenEnd: -1 }] })}><RiAddLine />{t(`contentAuthoring.${kind === 'vocabulary' ? 'LEXICAL' : 'GRAMMAR'}`)}</Button>)}</span></div>
    {!!sentence.contentWarnings?.length && <p className="finish-warning">{t('finish.noteWarning')} {sentence.contentWarnings.join('; ')}</p>}
  </fieldset>;
}
export function ClozePreview({ sentence, level, editable, onChange }: { sentence: AuthoringSentence; level: 'easy' | 'medium' | 'full'; editable?: boolean; onChange?: (value: Partial<AuthoringSentence>) => void }) {
  const { t } = useTranslation('admin');
  const words = textTokens(sentence.text), targets = level === 'full' ? words.map((_, i) => i) : sentence.cloze?.[level] ?? [];
  if (level === 'full' || !targets.length && !editable) return <p className="finish-cloze">{t('finish.fullDictation')}</p>;
  let end = 0;
  return <p className="finish-cloze">{words.map((word, index) => {
    const before = sentence.text.slice(end, word.index); end = word.index + word[0].length;
    const masked = targets.includes(index);
    return <span key={word.index}>{before}{editable && sentence.cloze ? <button type="button" aria-pressed={masked} title={word[0]} className={masked ? 'cloze-target' : ''} onClick={() => {
      const next = new Set(targets); if (masked) next.delete(index); else next.add(index);
      const easy = level === 'easy' ? [...next] : sentence.cloze!.easy.filter(i => next.has(i));
      const medium = level === 'medium' ? [...next] : [...new Set([...sentence.cloze!.medium, ...easy])];
      onChange?.({ cloze: { ...sentence.cloze!, easy: easy.sort((a, b) => a - b), medium: medium.sort((a, b) => a - b) }, clozeManual: true });
    }}>{word[0]}</button> : masked ? <span className="cloze-blank" title={word[0]} aria-label={t('finish.blank')}>{'＿'.repeat(Math.min(8, Math.max(3, word[0].length)))}</span> : word[0]}</span>;
  })}{sentence.text.slice(end)}</p>;
}
