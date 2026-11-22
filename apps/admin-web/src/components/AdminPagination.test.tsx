import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { AdminPagination } from './AdminPagination';

beforeEach(async () => { await i18n.changeLanguage('en'); });
afterEach(cleanup);
function show(props: Partial<React.ComponentProps<typeof AdminPagination>> = {}) {
  const onPageChange = vi.fn();
  const view = render(<I18nextProvider i18n={i18n}><AdminPagination page={0} pageCount={999} onPageChange={onPageChange} {...props} /></I18nextProvider>);
  return { ...view, onPageChange };
}
it('shows the first window, an ellipsis and a clickable last page', () => {
  const { onPageChange } = show();
  expect(screen.getByRole('button', { name: 'Page 1' })).toHaveAttribute('aria-current', 'page');
  expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
  expect(screen.getAllByText('…')).toHaveLength(1);
  fireEvent.click(screen.getByRole('button', { name: 'Page 999' }));
  expect(onPageChange).toHaveBeenCalledWith(998);
});
it('keeps both ends and the current page visible in the middle', () => {
  const { onPageChange } = show({ page: 499 });
  expect(screen.getAllByText('…')).toHaveLength(2);
  expect(screen.getByRole('button', { name: 'Page 500' })).toHaveAttribute('aria-current', 'page');
  fireEvent.click(screen.getByRole('button', { name: 'Next' }));
  expect(onPageChange).toHaveBeenCalledWith(500);
});
it('shows the last window and disables the next action at the end', () => {
  show({ page: 998 });
  expect(screen.getByRole('button', { name: 'Page 997' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
});
it('handles empty or single-page lists and disables navigation while loading', () => {
  show({ pageCount: 0, disabled: true });
  expect(screen.getAllByRole('button').every(button => button.hasAttribute('disabled'))).toBe(true);
  expect(screen.queryByText('…')).not.toBeInTheDocument();
});
it('never invents a last page or allows an unavailable cursor jump', () => {
  const { onPageChange } = show({ page: 7, pageCount: undefined, hasNext: true, canNavigate: page => [0, 7, 8].includes(page) });
  expect(screen.queryByRole('button', { name: 'Page 999' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Page 1' }));
  expect(onPageChange).toHaveBeenCalledWith(0);
});
it('uses translated accessible labels', async () => {
  await i18n.changeLanguage('vi');
  show({ pageCount: 3 });
  expect(screen.getByRole('navigation', { name: 'Phân trang' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Trang 3' })).toBeInTheDocument();
});
