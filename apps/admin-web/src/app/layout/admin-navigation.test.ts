import { expect, it } from 'vitest';
import { adminNavGroups, getNavTitleKey } from './admin-navigation';

it('names a lesson workspace separately from catalog and source creation', () => {
  expect(getNavTitleKey('/lessons/abc')).toBe('admin:authoringFlow.editorTitle');
  expect(getNavTitleKey('/lessons')).not.toBe('admin:authoringFlow.editorTitle');
  expect(getNavTitleKey('/lessons/new')).not.toBe('admin:authoringFlow.editorTitle');
  expect(getNavTitleKey('/lessons/categories')).not.toBe('admin:authoringFlow.editorTitle');
});

it('opens learner profiles from the sidebar and names the detail route', () => {
  const learners = adminNavGroups.find((group) => group.key === 'learners');
  expect(learners?.items.find((item) => item.key === 'profiles')?.path).toBe('/learners');
  expect(getNavTitleKey('/learners/example')).toBe('admin:navigation.profiles');
});

it('groups curriculum and voice roleplay under content studio', () => {
  const content = adminNavGroups.find((group) => group.key === 'content');
  expect(content?.items.find((item) => item.key === 'curriculum')?.path).toBe('/curriculum');
  expect(content?.items.find((item) => item.key === 'conversations')?.path).toBe('/conversations');
  expect(getNavTitleKey('/curriculum')).toBe('admin:navigation.curriculum');
  expect(getNavTitleKey('/conversations')).toBe('admin:navigation.conversations');
});

it('exposes separate plans, missions, achievements, notifications, and analytics routes', () => {
  expect(getNavTitleKey('/learners/progress')).toBe('admin:navigation.progress');
  expect(getNavTitleKey('/plans')).toBe('admin:navigation.plans');
  expect(getNavTitleKey('/missions')).toBe('admin:navigation.missions');
  expect(getNavTitleKey('/achievements')).toBe('admin:navigation.achievements');
  expect(getNavTitleKey('/notifications')).toBe('admin:navigation.notifications');
  expect(getNavTitleKey('/media')).toBe('admin:navigation.media');
});
