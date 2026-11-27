import { cleanup, render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { afterEach, expect, it } from 'vitest';
import { i18n } from '@/i18n';
import { DifficultyBadge, statusBadgeVariant } from './ContentBadges';

afterEach(cleanup);

it('uses the same difficulty colors for missions and the five question levels', async () => {
  await i18n.changeLanguage('en');
  render(<I18nextProvider i18n={i18n}>
    {['EASY', 'NORMAL', 'HARD', 1, 2, 3, 4, 5, null].map((difficulty) =>
      <DifficultyBadge key={difficulty ?? 'unclassified'} difficulty={difficulty} />)}
  </I18nextProvider>);

  expect(screen.getByText('Easy')).toHaveAttribute('data-variant', 'success');
  expect(screen.getByText('Standard')).toHaveAttribute('data-variant', 'warning');
  expect(screen.getByText('Challenging')).toHaveAttribute('data-variant', 'destructive');
  for (const [level, variant] of [[1, 'success'], [2, 'success'], [3, 'warning'], [4, 'destructive'], [5, 'destructive']]) {
    expect(screen.getByText(`Level ${level}`)).toHaveAttribute('data-variant', variant);
  }
  expect(screen.getByText('Unclassified')).toHaveAttribute('data-variant', 'outline');
});

it('maps lifecycle and quality statuses to shared colors', () => {
  expect(['PUBLISHED', 'SUCCEEDED', 'PASSED'].map(statusBadgeVariant)).toEqual(Array(3).fill('success'));
  expect(['DRAFT', 'TIMING_REVIEW', 'WARNING'].map(statusBadgeVariant)).toEqual(Array(3).fill('warning'));
  expect(['FAILED', 'DELETED', 'BLOCKED'].map(statusBadgeVariant)).toEqual(Array(3).fill('destructive'));
  expect(['RUNNING', 'DISABLED', 'HIDDEN'].map(statusBadgeVariant)).toEqual(Array(3).fill('secondary'));
});
