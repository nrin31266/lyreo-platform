import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { RiEditLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import type { LexiconDraft, Sense, Sound } from '../library/library.types';
import { PronunciationRecording } from './PronunciationEditor';
import { pronunciationGroups } from './pronunciationGroups';

type Language = 'vi' | 'en';

function definition(sense: Sense, language: Language) {
  return (language === 'vi' ? sense.translationVi : sense.definitionEn)?.trim();
}

function Meaning({ sense, language, onEdit }: { sense: Sense; language: Language; onEdit: (id: string, language: Language) => void }) {
  const { t } = useTranslation('admin');
  return <li lang={language} className="pl-1 leading-relaxed">
    <div className="flex items-start gap-2">
      <p className="min-w-0 flex-1 whitespace-pre-line">{definition(sense, language)}</p>
      <Button type="button" variant="ghost" size="icon-sm" aria-label={t('dictionary.editThisMeaning')} tooltip={t('dictionary.editThisMeaning')} onClick={() => onEdit(sense.id, language)}><RiEditLine aria-hidden="true" /></Button>
    </div>
    {language === 'vi' && sense.translationStatus === 'VERIFIED' && definition(sense, 'en') &&
      <p lang="en" className="mt-1 text-sm text-muted-foreground">{definition(sense, 'en')}</p>}
    {sense.examples.slice(0, 3).map((example, index) => typeof example.text === 'string' && <blockquote key={index} className="mt-2 border-l pl-3 text-sm text-muted-foreground">
      <p lang="en">{example.text}</p>
      {language === 'vi' && typeof example.translation === 'string' && <p>{example.translation}</p>}
    </blockquote>)}
  </li>;
}

function Pronunciation({ records, entry }: { records: Sound[]; entry: LexiconDraft }) {
  const { t } = useTranslation('admin');
  const first = records[0];
  const context = first.pronunciationContext;
  const item = context?.scope_status === 'EXPLICIT' && entry.items.find(row => row.id === first.itemId);
  const scope = item && t(`library.partsOfSpeech.${item.partOfSpeech}`, { defaultValue: item.partOfSpeech });
  const recordings = new Map<string, Sound[]>();
  for (const sound of records) {
    if (!sound.assetId && !sound.audioUrl) continue;
    const accent = sound.accent ?? 'unspecified';
    recordings.set(accent, [...(recordings.get(accent) ?? []), sound]);
  }
  return <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-sm">
    {context?.form && context.form !== entry.title && <span className="font-medium">{context.form}</span>}
    {first.ipa && <span className="font-mono">{first.ipa}</span>}
    {first.ipa && context?.notation_system !== 'IPA' && <span className="text-xs text-muted-foreground">{t('dictionary.sourceNotation')}</span>}
    <span className="text-muted-foreground">{context?.usage || scope || t('dictionary.unknownUsage')}</span>
    {[...recordings].map(([accent, sounds]) => <div key={accent} className="flex flex-wrap items-center gap-2">
      <span className="text-xs text-muted-foreground">{t(`dictionary.accents.${accent}`, { defaultValue: accent })}</span>
      <PronunciationRecording sound={sounds[0]} />
      {sounds.length > 1 && <details><summary className="cursor-pointer text-xs text-muted-foreground">{t('dictionary.otherRecordings', { count: sounds.length - 1 })}</summary>
        <div className="mt-2 flex flex-wrap gap-2">{sounds.slice(1).map(sound => <PronunciationRecording key={sound.id} sound={sound} />)}</div>
      </details>}
    </div>)}
  </div>;
}

export function DictionaryPreview({ entry, onEditSense }: { entry: LexiconDraft; onEditSense: (id: string, language: Language) => void }) {
  const { t, i18n } = useTranslation('admin');
  const [choice, setChoice] = useState<Language>();
  const language = choice ?? ((i18n.resolvedLanguage ?? i18n.language)?.toLowerCase().split('-')[0] === 'vi' ? 'vi' : 'en');
  const other = language === 'vi' ? 'en' : 'vi';
  const senses = entry.senses.filter(sense => definition(sense, language));
  const alternatives = entry.senses.some(sense => definition(sense, other));
  const sounds = pronunciationGroups(entry.title, entry.pronunciations)
    .filter(group => group[0].ipa || group.some(sound => sound.assetId || sound.audioUrl))
    .sort((a, b) => Number(b.some(sound => sound.assetId || sound.audioUrl)) - Number(a.some(sound => sound.assetId || sound.audioUrl)));
  const byPartOfSpeech = new Map<string, Sense[]>();
  for (const item of entry.items) {
    const meanings = senses.filter(sense => sense.itemId === item.id);
    if (meanings.length) byPartOfSpeech.set(item.partOfSpeech, [...(byPartOfSpeech.get(item.partOfSpeech) ?? []), ...meanings]);
  }
  return <section className="grid max-w-4xl gap-6 rounded-xl border bg-card p-4 sm:p-6" aria-label={t('dictionary.lookupPreview')}>
    <div className="flex flex-wrap items-start justify-between gap-3">
      <h2 className="text-2xl font-semibold tracking-tight">{entry.title}</h2>
      <div className="flex flex-wrap gap-2" role="group" aria-label={t('dictionary.readingMode')}>
        {(['vi', 'en'] as const).map(mode => <Button key={mode} size="sm" variant={mode === language ? 'secondary' : 'ghost'}
          aria-pressed={mode === language} onClick={() => setChoice(mode)}>{t(`dictionary.mode.${mode}`)}</Button>)}
      </div>
    </div>
    {sounds.length > 0 && <section className="grid gap-3 border-b pb-5" aria-label={t('library.pronunciations')}>
      <h3 className="text-sm font-semibold">{t('library.pronunciations')}</h3>
      {sounds.slice(0, 3).map(records => <Pronunciation key={records[0].id} records={records} entry={entry} />)}
      {sounds.length > 3 && <details><summary className="cursor-pointer text-sm font-medium text-muted-foreground">{t('dictionary.morePronunciations', { count: sounds.length - 3 })}</summary>
        <div className="mt-3 grid gap-3">{sounds.slice(3).map(records => <Pronunciation key={records[0].id} records={records} entry={entry} />)}</div>
      </details>}
    </section>}
    {senses.length === 0 && <div className="grid justify-items-start gap-2">
      <p className="text-sm text-muted-foreground">{t(`dictionary.missing.${language}`)}</p>
      {alternatives && <Button variant="outline" size="sm" onClick={() => setChoice(other)}>{t(`dictionary.showMode.${other}`)}</Button>}
    </div>}
    {[...byPartOfSpeech].map(([part, meanings]) => <section key={part} className="grid gap-3">
      <h3 className="text-sm font-semibold text-muted-foreground">{t(`library.partsOfSpeech.${part}`, { defaultValue: part })}</h3>
      <ol className="grid list-decimal gap-3 pl-5">
        {meanings.slice(0, 5).map(sense => <Meaning key={sense.id} sense={sense} language={language} onEdit={onEditSense} />)}
      </ol>
      {meanings.length > 5 && <details><summary className="cursor-pointer text-sm font-medium text-muted-foreground">{t('dictionary.moreMeanings', { count: meanings.length - 5 })}</summary>
        <ol start={6} className="mt-3 grid list-decimal gap-3 pl-5">{meanings.slice(5).map(sense => <Meaning key={sense.id} sense={sense} language={language} onEdit={onEditSense} />)}</ol>
      </details>}
    </section>)}
  </section>;
}
