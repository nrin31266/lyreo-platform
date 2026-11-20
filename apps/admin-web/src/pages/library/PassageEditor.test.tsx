import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nextProvider } from 'react-i18next';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { useState } from 'react';
import { PassageEditor, PassagePreview } from './PassageEditor';

beforeEach(async () => { await i18n.changeLanguage('en'); });
afterEach(cleanup);
it('allows harmless source serialization changes without a conversion step', async () => {
  const change=vi.fn();
  render(<I18nextProvider i18n={i18n}><PassageEditor value='<div style="color:red"><p>Original passage</p></div>' onChange={change} /></I18nextProvider>);
  expect(await screen.findByRole('textbox',{name:'Passage editor'})).toHaveAttribute('contenteditable','true');
  expect(change).not.toHaveBeenCalled();
  expect(screen.queryByRole('button',{name:/Use this editor version/i})).not.toBeInTheDocument();
  expect(screen.queryByRole('button',{name:'Add row'})).not.toBeInTheDocument();
});

it('serializes table spans and formatting, and can reopen the saved document without losing content', async () => {
  let saved = '<h2>Schedule</h2><p><strong>Bring notes</strong></p><ul><li><p>One item</p></li></ul><table><tbody><tr><th colspan="2"><p>Monday</p></th></tr><tr><td><p>9:00</p></td><td><p>Meeting</p></td></tr></tbody></table>';
  function Editor() {
    const [value, change] = useState(saved);
    return <><PassageEditor value={value} onChange={change} /><button onClick={() => { saved = value; }}>Save test document</button></>;
  }
  const first = render(<I18nextProvider i18n={i18n}><Editor /></I18nextProvider>);
  const conversion = screen.queryByRole('button', { name: /Use this editor version/i });
  if (conversion) await userEvent.click(conversion);
  await userEvent.click(screen.getByRole('button', { name: 'Save test document' }));
  expect(saved).toContain('colspan="2"');
  expect(saved).toContain('<strong>Bring notes</strong>');
  expect(saved).toContain('<li><p>One item</p></li>');
  first.unmount();
  render(<I18nextProvider i18n={i18n}><PassageEditor value={saved} onChange={vi.fn()} /></I18nextProvider>);
  const field = screen.getByRole('textbox', { name: 'Passage editor' });
  expect(field.querySelector('th')).toHaveAttribute('colspan', '2');
  expect(field).toHaveTextContent('Meeting');
  expect(screen.queryByRole('button', { name: /Use this editor version/i })).not.toBeInTheDocument();
});

it('keeps preview scripts and network content disabled', async () => {
  render(<I18nextProvider i18n={i18n}><PassagePreview html='<table><tr><td>Safe text</td></tr></table><script>alert(1)</script>' /></I18nextProvider>);
  const frame = screen.getByTitle('Passage preview');
  fireEvent.load(frame);
  await waitFor(() => expect(frame.style.height).toBe('120px'));
  expect(frame).toHaveAttribute('sandbox', 'allow-same-origin');
  expect(frame.getAttribute('srcdoc')).toContain("default-src 'none'");
  expect(frame.getAttribute('srcdoc')).toContain('<td>Safe text</td>');
});
