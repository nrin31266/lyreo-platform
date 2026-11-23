import { expect, it } from 'vitest';
import { adminNavGroups, getNavTitleKey } from './admin-navigation';
it('names a lesson workspace separately from catalog and source creation', () => {
  expect(getNavTitleKey('/lessons/abc')).toBe('admin:authoringFlow.editorTitle');
  expect(getNavTitleKey('/lessons')).not.toBe('admin:authoringFlow.editorTitle');
  expect(getNavTitleKey('/lessons/new')).not.toBe('admin:authoringFlow.editorTitle');
  expect(getNavTitleKey('/lessons/categories')).not.toBe('admin:authoringFlow.editorTitle');
});

it('opens learner profiles from the sidebar and names the detail route', () => {
  const learners = adminNavGroups.find(group => group.key === 'learners');
  expect(learners?.items.find(item => item.key === 'profiles')?.path).toBe('/learners');
  expect(getNavTitleKey('/learners/example')).toBe('admin:navigation.profiles');
});
