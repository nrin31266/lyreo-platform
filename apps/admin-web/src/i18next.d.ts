/**
 * Augment i18next's CustomTypeOptions with the Admin namespace resource types.
 *
 * This enables type-safe `t('key')` calls across the 'common' and 'admin' namespaces.
 * Per i18next v26 conventions, resource types are declared here rather than inline in
 * InitOptions so that the typed overload of `.init()` can resolve them correctly.
 */
import type { adminI18nResources } from './i18n';

declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common';
    resources: (typeof adminI18nResources)['en'];
  }
}
