import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { cn } from 'cn';
import { ipaAudioCredits, ipaExample, ipaGroups, ipaSoundId } from './ipa';
import { IpaGuide } from './IpaGuide';
import { IpaAudioButtons } from './IpaAudioButtons';
import { useIpaAudio } from './useIpaAudio';

export function PhoneticsPage() {
  const { t } = useTranslation('admin');
  const [params, setParams] = useSearchParams();
  const selected = ipaExample(params.get('sound') ?? '') ?? ipaExample('ŋ')!;
  const audio = useIpaAudio();
  const demo = ipaSoundId(selected.ipa);

  return <section className="mx-auto grid w-full max-w-7xl gap-6">
    <header className="grid gap-1">
      <h1 className="text-2xl font-semibold">{t('phonetics.title')}</h1>
      <p className="max-w-2xl text-sm text-muted-foreground">{t('phonetics.intro')}</p>
    </header>
    <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(270px,31%)]">
      <div className="grid gap-6">
        {Object.entries(ipaGroups).map(([group, examples]) => <section key={group} aria-labelledby={`ipa-${group}`} className="grid gap-2">
          <h2 id={`ipa-${group}`} className="font-semibold">{t(`phonetics.group.${group}`)}</h2>
          <ul className="grid grid-cols-3 gap-2 sm:grid-cols-5 xl:grid-cols-8">
            {examples.map(example => <li key={example.ipa}>
              <button type="button" aria-pressed={selected.ipa === example.ipa}
                aria-label={t('phonetics.selectSound', { sound: example.ipa, word: example.word })}
                className={cn('grid min-h-16 w-full content-center gap-0.5 rounded-lg border px-2 py-1 text-left hover:bg-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
                  selected.ipa === example.ipa && 'border-primary bg-primary/10')}
                onClick={() => { setParams({ sound: example.ipa }, { replace: true }); audio.toggle(ipaSoundId(example.ipa), example.word); }}>
                <span className="text-xl leading-tight">/{example.ipa}/</span>
                <span lang="en" className="truncate text-xs text-muted-foreground">{example.word}</span>
              </button>
            </li>)}
          </ul>
        </section>)}
      </div>
      <aside className="grid gap-4 rounded-xl border p-4 lg:sticky lg:top-4" aria-label={t('phonetics.sample')} id="ipa-sample">
        <div className="grid gap-1" role="status">
          <h2 className="text-3xl font-semibold">/{selected.ipa}/</h2>
          <p lang="en"><strong>{selected.word}</strong> <span className="text-muted-foreground">/{selected.transcription}/</span></p>
        </div>
        <IpaAudioButtons example={selected} audio={audio} />
        <IpaGuide sound={selected.ipa} />
        <div className="grid gap-1 border-t pt-3 text-xs text-muted-foreground">
          {[selected.word, demo].map(id => {
            const credit = ipaAudioCredits[id];
            return <p key={id}>
              <a href={credit.source} className="underline" target="_blank" rel="noreferrer">
                {t(id === selected.word ? 'phonetics.exampleSource' : 'phonetics.soundSource')}
              </a>{' · '}{credit.author}{' · '}
              {credit.licenseUrl
                ? <a href={credit.licenseUrl} className="underline" target="_blank" rel="noreferrer">{credit.license}</a> : credit.license}
              {credit.edited && ` · ${t('phonetics.editedRecording')}`}
            </p>;
          })}
        </div>
      </aside>
    </div>
  </section>;
}
