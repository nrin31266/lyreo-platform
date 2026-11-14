import { expect, it } from 'vitest';
import { getNavTitleKey } from './admin-navigation';
it('names a lesson workspace separately from catalog and source creation', () => {
  expect(getNavTitleKey('/lessons/abc')).toBe('admin:authoringFlow.editorTitle');
  expect(getNavTitleKey('/lessons')).not.toBe('admin:authoringFlow.editorTitle');
  expect(getNavTitleKey('/lessons/new')).not.toBe('admin:authoringFlow.editorTitle');
  expect(getNavTitleKey('/lessons/categories')).not.toBe('admin:authoringFlow.editorTitle');
});
