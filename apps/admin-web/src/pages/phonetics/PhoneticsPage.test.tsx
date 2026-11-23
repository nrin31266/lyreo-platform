import { readFileSync } from 'node:fs';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { adminI18nResources } from '@lyreo/i18n';
import { PhoneticsPage } from './PhoneticsPage';
import { ipaAudioCredits, ipaExample, ipaGroups, ipaSoundId } from './ipa';
import { ipaGuides } from './ipa-guides';
import { ipaGuideSources } from './ipa-guide-sources';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it('ships an attributed recording for the sound and its example, with both guides', () => {
  for (const { ipa, word } of Object.values(ipaGroups).flat()) {
    expect(ipaGuides[ipa]).toEqual({ en: expect.any(String), vi: expect.any(String) });
    expect(ipaGuideSources[ipa][1]).toMatch(/^[A-Za-z0-9_-]{11}$/);
    if (ipa !== 'ɔ') expect(ipaAudioCredits[ipaSoundId(ipa)].source).toBe(ipaAudioCredits[word].source);
    for (const id of [word, ipaSoundId(ipa)]) {
      expect(ipaAudioCredits[id].source).toMatch(/^https:\/\//);
      expect(ipaAudioCredits[id].author).toBeTruthy();
      expect(readFileSync(`public/ipa/${id}.ogg`).subarray(0, 4).toString()).toBe('OggS');
    }
  }
  expect(ipaExample('ɹ̠')?.ipa).toBe('ɹ');
  expect(ipaExample('unknown')).toBeUndefined();
});

it('opens the linked sound, plays its two distinct recordings, and switches guidance language', async () => {
  const sources: string[] = [];
  const players: HTMLMediaElement[] = [];
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(async function (this: HTMLMediaElement) {
    sources.push(this.src);
    players.push(this);
  });
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});
  const i18n = createInstance();
  await i18n.use(initReactI18next).init({ resources: adminI18nResources, lng: 'en', ns: ['admin'] });
  render(<I18nextProvider i18n={i18n}>
    <MemoryRouter initialEntries={['/phonetics?sound=%C5%8B']}><PhoneticsPage /></MemoryRouter>
  </I18nextProvider>);
  const sample = within(screen.getByRole('complementary', { name: 'Sound example' }));
  await userEvent.click(sample.getByRole('button', { name: 'Hear sound /ŋ/' }));
  await userEvent.click(sample.getByRole('button', { name: 'Hear ring' }));
  expect(sources.map(src => new URL(src).pathname)).toEqual(['/ipa/sound-14b.ogg', '/ipa/ring.ogg']);
  const sequence = sample.getByRole('button', { name: 'Hear sound → word' });
  await userEvent.click(sequence);
  fireEvent.ended(players[2]);
  expect(players[3]).toBe(players[2]);
  expect(sources.slice(-2).map(src => new URL(src).pathname)).toEqual(['/ipa/sound-14b.ogg', '/ipa/ring.ogg']);
  fireEvent.ended(players[3]);
  expect(sequence).toHaveAttribute('aria-pressed', 'false');
  await userEvent.click(sequence);
  await userEvent.click(sequence);
  fireEvent.ended(players[4]);
  expect(sources).toHaveLength(5);
  expect(sample.getByRole('link', { name: /Rachel’s English/ })).toHaveAttribute(
    'href', 'https://rachelsenglish.com/english-pronounce-ng-consonant/',
  );
  await userEvent.click(sample.getByRole('button', { name: 'Tiếng Việt' }));
  expect(sample.getByText(/giữ đầu lưỡi thấp/i)).toHaveAttribute('lang', 'vi');
  expect(sample.getByRole('link', { name: /Dan Hauer/ })).toHaveAttribute(
    'href', 'https://www.youtube.com/watch?v=wnxutoJOLoQ',
  );
});
