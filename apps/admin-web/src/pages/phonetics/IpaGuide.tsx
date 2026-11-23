import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { ipaGuides } from './ipa-guides';
import { ipaGuideSources } from './ipa-guide-sources';

export function IpaGuide({ sound }: { sound: string }) {
  const { t, i18n } = useTranslation('admin');
  const [preferred, setPreferred] = useState<'vi' | 'en'>();
  const language = preferred ?? (i18n.language.startsWith('vi') ? 'vi' : 'en');
  const guide = ipaGuides[sound];
  const source = ipaGuideSources[sound];
  if (!guide || !source) return null;
  const [englishLesson, vietnameseVideo] = source;
  const url = language === 'vi' ? `https://www.youtube.com/watch?v=${vietnameseVideo}`
    : englishLesson.startsWith('https:') ? englishLesson : `https://rachelsenglish.com/english-pronounce-${englishLesson}/`;
  return <div className="grid gap-1.5 text-sm">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <span className="font-medium">{t('phonetics.howTo')}</span>
      <div className="flex gap-1" role="group" aria-label={t('phonetics.guideLanguage')}>
        {(['vi', 'en'] as const).map(lang => <Button key={lang} type="button" size="sm"
          variant={language === lang ? 'secondary' : 'ghost'} aria-pressed={language === lang}
          onClick={() => setPreferred(lang)}>{t(lang === 'vi' ? 'phonetics.vietnameseGuide' : 'phonetics.englishGuide')}</Button>)}
      </div>
    </div>
    <p lang={language}>{guide[language]}</p>
    <a href={url} target="_blank" rel="noreferrer" lang={language}
      className="text-xs text-muted-foreground underline underline-offset-2">
      {language === 'vi' ? 'Dan Hauer · 42 ngày phát âm' : 'Rachel’s English · American pronunciation'}
    </a>
  </div>;
}
