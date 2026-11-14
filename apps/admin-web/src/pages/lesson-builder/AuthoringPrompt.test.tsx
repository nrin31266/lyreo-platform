import { act, cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { I18nextProvider } from 'react-i18next';
import { i18n } from '@/i18n';
import { AuthoringPrompt } from './AuthoringPrompt';

let user: ReturnType<typeof userEvent.setup>;
// jsdom has no scrolling; Radix Select scrolls focused options in a real browser.
beforeEach(async () => { Object.defineProperty(Element.prototype, 'scrollIntoView', {configurable:true,value:vi.fn()}); await i18n.changeLanguage('en'); user = userEvent.setup(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(Element.prototype, 'scrollIntoView'); });
function show(type: 'youtube' | 'text' | 'audio') {
  render(<I18nextProvider i18n={i18n}><AuthoringPrompt type={type} /></I18nextProvider>);
}
const baseValue = () => (screen.getByRole('textbox', {name:'Prompt to copy'}) as HTMLTextAreaElement).value;
async function chooseLast(label: string) {
  screen.getByRole('combobox',{name:label}).focus();
  await user.keyboard('{Enter}{End}{Enter}');
}
it.each(['youtube', 'text', 'audio'] as const)('copies the fixed %s prompt immediately with only a source selector', async type => {
  const copy=vi.spyOn(navigator.clipboard,'writeText').mockResolvedValue();
  show(type);await user.click(screen.getByRole('button',{name:'AI prompt'}));
  expect(screen.getAllByRole('combobox')).toHaveLength(1);
  expect(document.querySelector('input[type="number"]')).toBeNull();
  expect(screen.queryByRole('button',{name:/Optional preferences/})).not.toBeInTheDocument();
  expect(baseValue()).toContain(i18n.t(`admin:authoringPrompt.${type}.prompt`));
  expect(baseValue().endsWith(i18n.t('admin:authoringPrompt.randomNote'))).toBe(true);
  expect(copy).not.toHaveBeenCalled();
  expect(screen.getByRole('button',{name:'Copy prompt'})).toHaveFocus();
  const base=baseValue();await user.click(screen.getByRole('button',{name:'Copy prompt'}));
  expect(copy).toHaveBeenCalledExactlyOnceWith(base);
  await screen.findByRole('status');await user.keyboard('{Escape}');
  expect(screen.getByRole('button',{name:'AI prompt'})).toHaveFocus();
});
it('changes prompt source inside the dialog without closing it', async () => {
  show('text');await user.click(screen.getByRole('button',{name:'AI prompt'}));
  await chooseLast('Prompt type');
  expect(screen.getByRole('dialog',{name:'Prepare an Audio source with AI'})).toBeVisible();
  expect(baseValue()).toContain('MP3/WAV/M4A');
  await user.keyboard('{Escape}');await user.click(screen.getByRole('button',{name:'AI prompt'}));
  expect(screen.getByRole('dialog',{name:'Prepare an Audio source with AI'})).toBeVisible();
});
it('selects the fixed prompt for manual copy when clipboard access fails', async () => {
  vi.spyOn(navigator.clipboard,'writeText').mockRejectedValue(new DOMException('denied','NotAllowedError'));
  show('audio');await user.click(screen.getByRole('button',{name:'AI prompt'}));
  const base=baseValue();await user.click(screen.getByRole('button',{name:'Copy prompt'}));
  expect(await screen.findByRole('alert')).toHaveTextContent('Ctrl+C');
  const prompt=screen.getByRole('textbox',{name:'Prompt to copy'}) as HTMLTextAreaElement;
  expect(prompt.value).toBe(base);expect(prompt).toHaveFocus();
  expect(prompt.selectionEnd-prompt.selectionStart).toBe(base.length);
});
it('ignores a late clipboard result after the dialog is reopened', async () => {
  let done!:()=>void;
  vi.spyOn(navigator.clipboard,'writeText').mockImplementationOnce(()=>new Promise<void>(resolve=>{done=resolve;}));
  show('text');await user.click(screen.getByRole('button',{name:'AI prompt'}));
  await user.click(screen.getByRole('button',{name:'Copy prompt'}));
  await user.keyboard('{Escape}');await user.click(screen.getByRole('button',{name:'AI prompt'}));
  await act(async()=>done());expect(screen.queryByRole('status')).not.toBeInTheDocument();
});
it.each(['en','vi'])('leaves interaction choices to AI while keeping explanations and source rules in %s', locale => {
  const t=i18n.getFixedT(locale,'admin');const protocol=t('authoringPrompt.request');
  expect(protocol).not.toContain('{{');
  expect(protocol).not.toMatch(/random|ngẫu nhiên/i);
  expect(protocol).toContain('10');
  expect(t('authoringPrompt.randomNote')).toMatch(/random|ngẫu nhiên/i);
  for(const mode of ['text','youtube','audio']) {
    const prompt=t(`authoringPrompt.${mode}.prompt`);
    expect(prompt).not.toContain('{{');expect(prompt).not.toMatch(/random|ngẫu nhiên/i);
    expect(prompt.toLocaleLowerCase()).toContain(locale==='vi' ? 'ý nghĩa tiêu đề' : 'title meaning');
  }
  expect(t('authoringPrompt.text.prompt')).toContain(locale==='vi' ? 'một giọng Kokoro' : 'one Kokoro voice');
  expect(t('authoringPrompt.youtube.prompt')).toContain('https://www.youtube.com/watch?v=');
  expect(t('authoringPrompt.audio.prompt')).toContain('30 MiB');
});
