import { useTranslation } from 'react-i18next';
import { LibraryList } from '@/pages/library/LibraryWorkspace';

export function LexiconPage() {
  const { t } = useTranslation('admin');
  return <LibraryList endpoint="/api/v1/admin/lexicon" route="/lexicon" title={t('navigation.lexicon')} />;
}
