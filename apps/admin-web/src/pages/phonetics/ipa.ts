import audioCredits from './ipa-audio.json';

export type IpaExample = { ipa: string; word: string; transcription: string };
export const ipaGroups = {
  consonants: [
    { ipa: 'p', word: 'pen', transcription: 'pɛn' },
    { ipa: 'b', word: 'bee', transcription: 'bi' },
    { ipa: 't', word: 'tea', transcription: 'ti' },
    { ipa: 'd', word: 'dog', transcription: 'dɔɡ' },
    { ipa: 'k', word: 'cat', transcription: 'kæt' },
    { ipa: 'ɡ', word: 'go', transcription: 'ɡoʊ' },
    { ipa: 'tʃ', word: 'chair', transcription: 'tʃɛɹ' },
    { ipa: 'dʒ', word: 'judge', transcription: 'dʒʌdʒ' },
    { ipa: 'f', word: 'fish', transcription: 'fɪʃ' },
    { ipa: 'v', word: 'van', transcription: 'væn' },
    { ipa: 'θ', word: 'thin', transcription: 'θɪn' },
    { ipa: 'ð', word: 'this', transcription: 'ðɪs' },
    { ipa: 's', word: 'sun', transcription: 'sʌn' },
    { ipa: 'z', word: 'zoo', transcription: 'zu' },
    { ipa: 'ʃ', word: 'shoe', transcription: 'ʃu' },
    { ipa: 'ʒ', word: 'vision', transcription: 'ˈvɪʒən' },
    { ipa: 'm', word: 'man', transcription: 'mæn' },
    { ipa: 'n', word: 'no', transcription: 'noʊ' },
    { ipa: 'ŋ', word: 'ring', transcription: 'ɹɪŋ' },
    { ipa: 'h', word: 'hat', transcription: 'hæt' },
    { ipa: 'l', word: 'light', transcription: 'laɪt' },
    { ipa: 'ɹ', word: 'red', transcription: 'ɹɛd' },
    { ipa: 'j', word: 'yes', transcription: 'jɛs' },
    { ipa: 'w', word: 'we', transcription: 'wi' },
  ],
  vowels: [
    { ipa: 'i', word: 'see', transcription: 'si' },
    { ipa: 'ɪ', word: 'sit', transcription: 'sɪt' },
    { ipa: 'ɛ', word: 'bed', transcription: 'bɛd' },
    { ipa: 'æ', word: 'cat', transcription: 'kæt' },
    { ipa: 'ɑ', word: 'father', transcription: 'ˈfɑðɚ' },
    { ipa: 'ɔ', word: 'north', transcription: 'nɔɹθ' },
    { ipa: 'ʊ', word: 'book', transcription: 'bʊk' },
    { ipa: 'u', word: 'moon', transcription: 'mun' },
    { ipa: 'ʌ', word: 'cup', transcription: 'kʌp' },
    { ipa: 'ə', word: 'about', transcription: 'əˈbaʊt' },
    { ipa: 'ɝ', word: 'bird', transcription: 'bɝd' },
    { ipa: 'ɚ', word: 'teacher', transcription: 'ˈtitʃɚ' },
  ],
  diphthongs: [
    { ipa: 'eɪ', word: 'day', transcription: 'deɪ' },
    { ipa: 'aɪ', word: 'my', transcription: 'maɪ' },
    { ipa: 'ɔɪ', word: 'boy', transcription: 'bɔɪ' },
    { ipa: 'oʊ', word: 'go', transcription: 'ɡoʊ' },
    { ipa: 'aʊ', word: 'now', transcription: 'naʊ' },
  ],
} satisfies Record<string, IpaExample[]>;

const aliases: Record<string, string> = { r: 'ɹ', g: 'ɡ', 'ɹ̠': 'ɹ', 'ʧ': 'tʃ', 'ʤ': 'dʒ', 't͡ʃ': 'tʃ', 'd͡ʒ': 'dʒ' };
const examples = Object.values(ipaGroups).flat();
export function ipaExample(symbol: string): IpaExample | undefined {
  return examples.find(example => example.ipa === (aliases[symbol] ?? symbol));
}

export const ipaSoundId = (sound: string) =>
  'sound-' + Array.from(sound, character => character.codePointAt(0)!.toString(16)).join('-');

export const ipaAudioCredits: Record<string, { source: string; author: string; license: string; licenseUrl?: string; edited?: boolean }> = audioCredits;
