import { useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { CategoriesPage } from './CategoriesPage';
import { ChannelsPage } from './ChannelsPage';

export function OrganizationPage() {
  const { t } = useTranslation('admin');
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'channels' ? 'channels' : 'categories';
  return <section className="grid gap-4">
    <h1>{t('catalog.organization')}</h1>
    <Tabs value={tab} onValueChange={value => setParams({ tab: value })}>
      <TabsList aria-label={t('catalog.organization')}>
        <TabsTrigger value="categories">{t('catalog.categories')}</TabsTrigger>
        <TabsTrigger value="channels">{t('catalog.channels')}</TabsTrigger>
      </TabsList>
      <TabsContent value="categories"><CategoriesPage embedded /></TabsContent>
      <TabsContent value="channels"><ChannelsPage embedded /></TabsContent>
    </Tabs>
  </section>;
}
