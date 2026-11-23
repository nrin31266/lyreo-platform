import { useTranslation } from 'react-i18next';
import { ipaGuides } from './ipa-guides';
import { bbcSoundsOfEnglish, ieltsFighterIpa, type IpaExample } from './ipa';

export function IpaGuide({ example, hideHeading = false, hideSource = false }: { example: IpaExample; hideHeading?: boolean; hideSource?: boolean }) {
  const { t, i18n } = useTranslation('admin');
  const language = i18n.language.startsWith('vi') ? 'vi' : 'en';
  const guide = ipaGuides[example.ipa];
  const url = language === 'vi' ? ieltsFighterIpa : `${bbcSoundsOfEnglish}&t=${example.bbcStart}s`;
  return <div className="grid gap-1.5 text-sm">
    {!hideHeading && <span className="font-medium">{t('phonetics.howTo')}</span>}
    <p lang={language}>{guide[language]}</p>
    {!hideSource && <a href={url} target="_blank" rel="noreferrer" lang={language}
      className="text-xs text-muted-foreground underline underline-offset-2">
      {language === 'vi' ? 'IELTS Fighter · Bảng IPA 44 âm' : 'BBC Learning English · The Sounds of English'}
    </a>}
  </div>;
}
