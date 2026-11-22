import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { Button } from './ui/button';

afterEach(cleanup);
it('explains enabled actions on keyboard focus and preserves the click handler', async () => {
  const click=vi.fn();
  render(<Button tooltip="Save a draft without publishing" onClick={click}>Save</Button>);
  await userEvent.tab();
  expect(await screen.findByRole('tooltip')).toHaveTextContent('Save a draft without publishing');
  await userEvent.keyboard('{Enter}');
  expect(click).toHaveBeenCalledOnce();
});
it('explains a disabled action without making the button actionable', async () => {
  const click=vi.fn();
  render(<Button disabled tooltip="Save the draft first" onClick={click}>Check quality</Button>);
  await userEvent.hover(screen.getByRole('button').parentElement!);
  expect(await screen.findByRole('tooltip')).toHaveTextContent('Save the draft first');
  expect(screen.getByRole('button')).toBeDisabled();
  expect(click).not.toHaveBeenCalled();
});
