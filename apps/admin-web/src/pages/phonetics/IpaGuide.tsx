import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { ipaGuides } from './ipa-guides';
import { bbcSoundsOfEnglish, ieltsFighterIpa, type IpaExample } from './ipa';

export function IpaGuide({ example, hideHeading = false }: { example: IpaExample; hideHeading?: boolean }) {
  const { t, i18n } = useTranslation('admin');
  const [preferred, setPreferred] = useState<'vi' | 'en'>();
  const language = preferred ?? (i18n.language.startsWith('vi') ? 'vi' : 'en');
  const guide = ipaGuides[example.ipa];
  const url = language === 'vi' ? ieltsFighterIpa : `${bbcSoundsOfEnglish}&t=${example.bbcStart}s`;
  return <div className="grid gap-1.5 text-sm">
    <div className="flex flex-wrap items-center justify-between gap-2">
      {!hideHeading && <span className="font-medium">{t('phonetics.howTo')}</span>}
      <div className="flex gap-1" role="group" aria-label={t('phonetics.guideLanguage')}>
        {(['vi', 'en'] as const).map(lang => <Button key={lang} type="button" size="sm"
          variant={language === lang ? 'secondary' : 'ghost'} aria-pressed={language === lang}
          onClick={() => setPreferred(lang)}>{t(lang === 'vi' ? 'phonetics.vietnameseGuide' : 'phonetics.englishGuide')}</Button>)}
      </div>
    </div>
    <p lang={language}>{guide[language]}</p>
    <a href={url} target="_blank" rel="noreferrer" lang={language}
      className="text-xs text-muted-foreground underline underline-offset-2">
      {language === 'vi' ? 'IELTS Fighter · Bảng IPA 44 âm' : 'BBC Learning English · The Sounds of English'}
    </a>
  </div>;
}
