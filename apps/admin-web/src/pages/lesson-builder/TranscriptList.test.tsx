import { useState } from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { TranscriptSentence } from './lesson-builder.types';
import { TranscriptList } from './TranscriptList';

vi.mock('@tanstack/react-virtual', async importOriginal => {
  const actual = await importOriginal<typeof import('@tanstack/react-virtual')>();
  return { ...actual, useVirtualizer: (options: Parameters<typeof actual.useVirtualizer>[0]) => actual.useVirtualizer({
    ...options,
    observeElementRect: (_, callback) => { callback({ width: 800, height: 300 }); return () => {}; },
    measureElement: () => 128,
  }) };
});
afterEach(cleanup);

function Editor() {
  const [sentences, setSentences] = useState<TranscriptSentence[]>(Array.from({ length: 100 }, (_, index) => ({ id: `s${index}`, text: `Sentence ${index}`, usage: 'LEARN' })));
  const [filtered, setFiltered] = useState(false);
  const visibleRows = filtered ? [0] : sentences.map((_, index) => index);
  return <><button onClick={() => setFiltered(!filtered)}>Filter</button>
    <TranscriptList sentences={sentences} visibleRows={visibleRows} filtered={filtered}>
      {(sentence, index) => <input aria-label={`Sentence ${index}`} value={sentence.text} onChange={event => setSentences(old => old.map((item, i) => i === index ? { ...item, text: event.target.value } : item))} />}
    </TranscriptList></>;
}

it('mounts a bounded window and retains edits across filtering', () => {
  render(<Editor />);
  expect(screen.getAllByRole('textbox').length).toBeLessThan(15);
  expect(screen.queryByRole('textbox', { name: 'Sentence 99' })).not.toBeInTheDocument();
  fireEvent.change(screen.getByRole('textbox', { name: 'Sentence 0' }), { target: { value: 'Edited text' } });
  fireEvent.click(screen.getByRole('button', { name: 'Filter' }));
  expect(screen.getAllByRole('textbox')).toHaveLength(1);
  fireEvent.click(screen.getByRole('button', { name: 'Filter' }));
  expect(screen.getByRole('textbox', { name: 'Sentence 0' })).toHaveValue('Edited text');
  expect(screen.getAllByRole('textbox').length).toBeLessThan(15);
});

it('keeps a focused input mounted outside the scrolling window', () => {
  render(<Editor />);
  const input = screen.getByRole('textbox', { name: 'Sentence 0' });
  act(() => input.focus());
  const list = screen.getByRole('list');
  fireEvent.scroll(list, { target: { scrollTop: 5000 } });
  expect(screen.getByRole('textbox', { name: 'Sentence 40' })).toBeInTheDocument();
  expect(input).toHaveFocus();
  expect(screen.getByRole('textbox', { name: 'Sentence 0' })).toBe(input);
  expect(screen.getAllByRole('textbox').length).toBeLessThan(15);
});
