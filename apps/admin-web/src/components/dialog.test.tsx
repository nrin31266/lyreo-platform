import { useState } from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from './ui/dialog';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: () => 'Close' }) }));
afterEach(cleanup);

function Editor() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button onClick={() => setOpen(true)}>Edit lesson</button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent closeLabel="Close editor">
          <DialogTitle>Edit lesson</DialogTitle>
          <DialogDescription>Change display metadata.</DialogDescription>
          <input aria-label="Lesson title" />
        </DialogContent>
      </Dialog>
    </>
  );
}

it('restores keyboard focus when a controlled dialog has no DialogTrigger', async () => {
  const user = userEvent.setup();
  render(<Editor />);
  const trigger = screen.getByRole('button', { name: 'Edit lesson' });
  await user.click(trigger);
  expect(screen.getByRole('textbox', { name: 'Lesson title' })).toHaveFocus();
  await user.click(screen.getByRole('button', { name: 'Close editor' }));
  await waitFor(() => expect(trigger).toHaveFocus());
});
