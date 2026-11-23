import { readFileSync } from 'node:fs';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { adminI18nResources } from '@lyreo/i18n';
import { PhoneticsPage } from './PhoneticsPage';
import { internationalIpaChart, ipaAudioCredits, ipaExample, ipaGroups, ipaSoundUrl } from './ipa';
import { ipaGuides } from './ipa-guides';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it('covers all 44 symbols and supplements the duplicate /ʊə/ recording', () => {
  const examples = Object.values(ipaGroups).flat();
  expect(ipaGroups.vowels).toHaveLength(12);
  expect(ipaGroups.diphthongs).toHaveLength(8);
  expect(ipaGroups.consonants).toHaveLength(24);
  expect(new Set(examples.map(example => example.ipa)).size).toBe(44);
  for (const example of examples) {
    const { ipa, word } = example;
    expect(ipaGuides[ipa]).toEqual({ en: expect.any(String), vi: expect.any(String) });
    if (example.lessonUrl) {
      expect(ipa).toBe('ʊə');
      expect(ipaSoundUrl(example)).toMatch(/^https:\/\/www\.speechactive\.com\//);
    } else {
      expect(new URL(ipaSoundUrl(example)!).hostname).toBe('st.ielts-fighter.com');
      expect(ipaAudioCredits[word].source).toMatch(/^https:\/\//);
      expect(ipaAudioCredits[word].author).toBeTruthy();
      expect(readFileSync(`public/ipa/${word}.ogg`).subarray(0, 4).toString()).toBe('OggS');
    }
  }
  expect(ipaExample('ɹ̠')?.ipa).toBe('r');
  expect(ipaExample('i:')?.ipa).toBe('iː');
  expect(ipaExample('oʊ')?.ipa).toBe('əʊ');
  expect(ipaExample('ɝ')).toBeUndefined();
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
  await userEvent.click(sample.getByRole('button', { name: 'Hear ring /rɪŋ/' }));
  expect(sources[0]).toBe(ipaSoundUrl(ipaExample('ŋ')!));
  expect(new URL(sources[1]).pathname).toBe('/ipa/ring.ogg');
  const sequence = sample.getByRole('button', { name: 'Hear sound → word' });
  await userEvent.click(sequence);
  fireEvent.ended(players[2]);
  expect(players[3]).toBe(players[2]);
  expect(sources[2]).toBe(ipaSoundUrl(ipaExample('ŋ')!));
  expect(new URL(sources[3]).pathname).toBe('/ipa/ring.ogg');
  fireEvent.ended(players[3]);
  expect(sequence).toHaveAttribute('aria-pressed', 'false');
  await userEvent.click(sequence);
  await userEvent.click(sequence);
  fireEvent.ended(players[4]);
  expect(sources).toHaveLength(5);
  expect(sample.getByRole('link', { name: /BBC Learning English/ })).toHaveAttribute(
    'href', 'https://www.youtube.com/watch?v=QxQUapA-2w4&t=3750s',
  );
  expect(sample.queryByRole('button', { name: 'Tiếng Việt' })).not.toBeInTheDocument();
  expect(sample.getByText(/keep the tongue tip low/i)).toHaveAttribute('lang', 'en');
  expect(sample.queryByRole('link', { name: /IELTS Fighter/ })).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'International IPA chart' })).toHaveAttribute('href', internationalIpaChart);
  await userEvent.click(screen.getByRole('button', { name: 'Review /ʊə/' }));
  const cureSound = sample.getByRole('button', { name: 'Hear sound /ʊə/' });
  expect(cureSound).toBeInTheDocument();
  expect(sample.queryByRole('button', { name: /Hear tour/ })).not.toBeInTheDocument();
  expect(sample.getByRole('link', { name: /BBC Learning English/ })).toHaveAttribute(
    'href', 'https://www.youtube.com/watch?v=QxQUapA-2w4&t=1205s',
  );
  await userEvent.click(cureSound);
  expect(sources.at(-1)).toBe(ipaSoundUrl(ipaExample('ʊə')!));
});
