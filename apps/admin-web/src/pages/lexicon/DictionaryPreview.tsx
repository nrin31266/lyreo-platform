import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import type { LexiconDraft, Sense } from '../library/library.types';
import { PronunciationRecording } from './PronunciationEditor';
import { pronunciationGroups } from './pronunciationGroups';

type Language = 'vi' | 'en';

function definition(sense: Sense, language: Language) {
  return (language === 'vi' ? sense.translationVi : sense.definitionEn)?.trim();
}

export function DictionaryPreview({ entry }: { entry: LexiconDraft }) {
  const { t, i18n } = useTranslation('admin');
  const [choice, setChoice] = useState<Language>();
  const language = choice ?? ((i18n.resolvedLanguage ?? i18n.language)?.toLowerCase().split('-')[0] === 'vi' ? 'vi' : 'en');
  const other = language === 'vi' ? 'en' : 'vi';
  const senses = entry.senses.filter(sense => definition(sense, language));
  const alternatives = entry.senses.some(sense => definition(sense, other));
  const groups = pronunciationGroups(entry.title, entry.pronunciations);
  return <section className="grid max-w-4xl gap-5 rounded-xl border bg-card p-4 sm:p-6" aria-label={t('dictionary.lookupPreview')}>
    <div className="flex flex-wrap items-center justify-between gap-3">
      <p className="text-xl font-semibold">{entry.title}</p>
      <div className="flex gap-2" role="group" aria-label={t('dictionary.readingMode')}>
        {(['vi', 'en'] as const).map(mode => <Button key={mode} size="sm" variant={mode === language ? 'secondary' : 'ghost'}
          aria-pressed={mode === language} onClick={() => setChoice(mode)}>{t(`dictionary.mode.${mode}`)}</Button>)}
      </div>
    </div>
    {groups.length > 0 && <div className="grid gap-2 border-b pb-4">
      <h3 className="text-sm font-semibold">{t('library.pronunciations')}</h3>
      {groups.map(records => {
        const first = records[0];
        const context = first.pronunciationContext;
        const item = context?.scope_status === 'EXPLICIT' && entry.items.find(row => row.id === first.itemId);
        const scope = item && t(`library.partsOfSpeech.${item.partOfSpeech}`, { defaultValue: item.partOfSpeech });
        const primary = new Map<string, typeof first>();
        for (const sound of records) {
          if ((sound.assetId || sound.audioUrl) && !primary.has(sound.accent ?? 'unspecified')) {
            primary.set(sound.accent ?? 'unspecified', sound);
          }
        }
        return <div key={first.id} className="flex flex-wrap items-center gap-x-3 gap-y-2 text-sm">
          <span className="font-medium">{context?.form || entry.title}</span>
          {first.ipa && <span className="font-mono">{first.ipa}</span>}
          <span className="text-muted-foreground">{context?.usage || scope || t('dictionary.unknownUsage')}</span>
          {[...primary].map(([accent, sound]) => <div key={sound.id} className="flex items-center gap-2">
            <span className="text-xs text-muted-foreground">{t(`dictionary.accents.${accent}`, { defaultValue: accent })}</span>
            <PronunciationRecording sound={sound} />
          </div>)}
        </div>;
      })}
    </div>}
    {senses.length === 0 && <div className="grid justify-items-start gap-2">
      <p className="text-sm text-muted-foreground">{t(`dictionary.missing.${language}`)}</p>
      {alternatives && <Button variant="outline" size="sm" onClick={() => setChoice(other)}>{t(`dictionary.showMode.${other}`)}</Button>}
    </div>}
    {entry.items.map(item => {
      const meanings = senses.filter(sense => sense.itemId === item.id);
      if (!meanings.length) return null;
      return <section key={item.id} className="grid gap-2">
        <h3 className="text-sm font-semibold text-muted-foreground">{t(`library.partsOfSpeech.${item.partOfSpeech}`, { defaultValue: item.partOfSpeech })}</h3>
        <ol className="list-decimal space-y-3 pl-5">
          {meanings.map(sense => <li key={sense.id} lang={language}>
            <p className="whitespace-pre-line">{definition(sense, language)}</p>
            {language === 'vi' && sense.translationStatus === 'VERIFIED' && definition(sense, 'en') && <p lang="en" className="mt-1 text-sm text-muted-foreground">{definition(sense, 'en')}</p>}
            {sense.examples.slice(0, 3).map((example, index) => typeof example.text === 'string' && <blockquote key={index} className="mt-2 border-l pl-3 text-sm text-muted-foreground">
              <p lang="en">{example.text}</p>
              {language === 'vi' && typeof example.translation === 'string' && <p>{example.translation}</p>}
            </blockquote>)}
          </li>)}
        </ol>
      </section>;
    })}
  </section>;
}
