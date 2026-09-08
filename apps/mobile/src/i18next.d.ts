/**
 * Augment i18next's CustomTypeOptions with the Mobile namespace resource types.
 *
 * This enables type-safe `t('key')` calls across the 'common' and 'mobile' namespaces.
 * Per i18next v26 conventions, resource types are declared here rather than inline in
 * InitOptions so that the typed overload of `.init()` can resolve them correctly.
 *
 * ResourceNamespaceMap is the per-package mechanism for monorepos; CustomTypeOptions
 * here covers app-level defaultNS and resource shape.
 */
import type { mobileI18nResources } from './i18n';

declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common';
    resources: (typeof mobileI18nResources)['en'];
  }
}
