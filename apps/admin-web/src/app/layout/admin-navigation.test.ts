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

it('opens curriculum workspace from the sidebar and resolves its title key', () => {
  const learning = adminNavGroups.find(group => group.key === 'learning');
  expect(learning?.items.find(item => item.key === 'curriculum')?.path).toBe('/curriculum');
  expect(learning?.items.find(item => item.key === 'activities')?.path).toBe('/activities');
  expect(getNavTitleKey('/curriculum')).toBe('admin:navigation.curriculum');
  expect(getNavTitleKey('/activities')).toBe('admin:navigation.activities');
});

it('exposes live operational routes across learners, engagement, and media', () => {
  expect(getNavTitleKey('/learners/progress')).toBe('admin:navigation.progress');
  expect(getNavTitleKey('/rewards')).toBe('admin:navigation.rewards');
  expect(getNavTitleKey('/notifications')).toBe('admin:navigation.notifications');
  expect(getNavTitleKey('/conversations')).toBe('admin:navigation.conversations');
  expect(getNavTitleKey('/media')).toBe('admin:navigation.media');
});
