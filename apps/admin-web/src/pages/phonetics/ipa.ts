import audioCredits from './ipa-audio.json';

export const internationalIpaChart = 'https://www.internationalphoneticassociation.org/IPAcharts/IPA_charts_TI/IPA_charts_TI.html';
export const ieltsFighterIpa = 'https://ielts-fighter.com/tin-tuc/bang-phien-am-tieng-anh-ipa_mt1567386908.html';
const ieltsFighterAudio = 'https://st.ielts-fighter.com/src/ielts-fighter/2019/09/09/';
export const bbcSoundsOfEnglish = 'https://www.youtube.com/watch?v=QxQUapA-2w4';

export type IpaExample = {
  ipa: string;
  word: string;
  transcription: string;
  bbcStart: number;
  soundFile: string;
  soundSource?: string;
  wordAudio?: { url: string; source: string };
};

export const ipaGroups: Record<'vowels' | 'diphthongs' | 'consonants', IpaExample[]> = {
  vowels: [
    { ipa: 'ɪ', word: 'kid', transcription: 'kɪd', bbcStart: 131, soundFile: 'i ngắn.mp3' },
    { ipa: 'iː', word: 'sea', transcription: 'siː', bbcStart: 0, soundFile: 'i dài.mp3' },
    { ipa: 'e', word: 'bed', transcription: 'bed', bbcStart: 345, soundFile: 'e.mp3' },
    { ipa: 'ə', word: 'banana', transcription: 'bəˈnɑːnə', bbcStart: 455, soundFile: 'ơ ngắn.mp3' },
    { ipa: 'ɜː', word: 'birthday', transcription: 'ˈbɜːθdeɪ', bbcStart: 696, soundFile: 'ơ dài.mp3' },
    { ipa: 'ʊ', word: 'good', transcription: 'ɡʊd', bbcStart: 251, soundFile: 'u ngắn.mp3' },
    { ipa: 'uː', word: 'goose', transcription: 'ɡuːs', bbcStart: 292, soundFile: 'u dài.mp3' },
    { ipa: 'ɒ', word: 'hot', transcription: 'hɒt', bbcStart: 996, soundFile: 'o ngắn.mp3' },
    { ipa: 'ɔː', word: 'law', transcription: 'lɔː', bbcStart: 750, soundFile: 'o dài.mp3' },
    { ipa: 'ʌ', word: 'love', transcription: 'lʌv', bbcStart: 846, soundFile: 'a ngắn.mp3' },
    { ipa: 'ɑː', word: 'father', transcription: 'ˈfɑːðə', bbcStart: 957, soundFile: 'a dài.mp3' },
    { ipa: 'æ', word: 'bad', transcription: 'bæd', bbcStart: 800, soundFile: 'ae.mp3' },
  ],
  diphthongs: [
    { ipa: 'ɪə', word: 'near', transcription: 'nɪə', bbcStart: 1036, soundFile: 'ie.mp3' },
    { ipa: 'eə', word: 'hair', transcription: 'heə', bbcStart: 1423, soundFile: 'ea.mp3' },
    { ipa: 'eɪ', word: 'day', transcription: 'deɪ', bbcStart: 1103, soundFile: 'ei.mp3' },
    { ipa: 'ɔɪ', word: 'boy', transcription: 'bɔɪ', bbcStart: 1259, soundFile: 'oi.mp3' },
    { ipa: 'aɪ', word: 'try', transcription: 'traɪ', bbcStart: 1526, soundFile: 'aɪ.mp3' },
    { ipa: 'əʊ', word: 'coat', transcription: 'kəʊt', bbcStart: 1313, soundFile: 'əʊ.mp3' },
    { ipa: 'aʊ', word: 'mouth', transcription: 'maʊθ', bbcStart: 1586, soundFile: 'aʊ.mp3' },
    {
      ipa: 'ʊə', word: 'tour', transcription: 'tʊə', bbcStart: 1205,
      soundFile: 'https://elc.polyu.edu.hk/sounds/pron/ipa/OO-ER.mp3',
      soundSource: 'https://elc.polyu.edu.hk/sounds/lesson4-2.htm',
      wordAudio: {
        url: 'https://www.oxfordlearnersdictionaries.com/media/english/uk_pron/t/tou/tour_/tour__gb_5.mp3',
        source: 'https://www.oxfordlearnersdictionaries.com/definition/english/tour_1',
      },
    },
  ],
  consonants: [
    { ipa: 'p', word: 'pen', transcription: 'pen', bbcStart: 1723, soundFile: 'p.mp3' },
    { ipa: 'b', word: 'back', transcription: 'bæk', bbcStart: 1860, soundFile: 'b.mp3' },
    { ipa: 't', word: 'tea', transcription: 'tiː', bbcStart: 1994, soundFile: 't.mp3' },
    { ipa: 'd', word: 'day', transcription: 'deɪ', bbcStart: 2124, soundFile: 'd.mp3' },
    { ipa: 'tʃ', word: 'match', transcription: 'mætʃ', bbcStart: 2249, soundFile: 't∫.mp3' },
    { ipa: 'dʒ', word: 'age', transcription: 'eɪdʒ', bbcStart: 2354, soundFile: 'dʒ.mp3' },
    { ipa: 'k', word: 'key', transcription: 'kiː', bbcStart: 2459, soundFile: 'k.mp3' },
    { ipa: 'ɡ', word: 'get', transcription: 'ɡet', bbcStart: 2579, soundFile: 'g.mp3' },
    { ipa: 'f', word: 'fat', transcription: 'fæt', bbcStart: 2684, soundFile: 'f.mp3' },
    { ipa: 'v', word: 'move', transcription: 'muːv', bbcStart: 2780, soundFile: 'v.mp3' },
    { ipa: 'ð', word: 'this', transcription: 'ðɪs', bbcStart: 3041, soundFile: 'ð.mp3' },
    { ipa: 'θ', word: 'thin', transcription: 'θɪn', bbcStart: 2970, soundFile: 'θ.mp3' },
    { ipa: 's', word: 'soon', transcription: 'suːn', bbcStart: 3113, soundFile: 's.mp3' },
    { ipa: 'z', word: 'buzz', transcription: 'bʌz', bbcStart: 3209, soundFile: 'z.mp3' },
    { ipa: 'ʃ', word: 'ship', transcription: 'ʃɪp', bbcStart: 3305, soundFile: '∫.mp3' },
    { ipa: 'ʒ', word: 'vision', transcription: 'ˈvɪʒən', bbcStart: 3462, soundFile: 'ʒ.mp3' },
    { ipa: 'm', word: 'mean', transcription: 'miːn', bbcStart: 3506, soundFile: 'm.mp3' },
    { ipa: 'n', word: 'nice', transcription: 'naɪs', bbcStart: 3625, soundFile: 'n.mp3' },
    { ipa: 'ŋ', word: 'ring', transcription: 'rɪŋ', bbcStart: 3750, soundFile: 'η.mp3' },
    { ipa: 'h', word: 'hot', transcription: 'hɒt', bbcStart: 3879, soundFile: 'h.mp3' },
    { ipa: 'l', word: 'light', transcription: 'laɪt', bbcStart: 3945, soundFile: 'l.mp3' },
    { ipa: 'r', word: 'right', transcription: 'raɪt', bbcStart: 4160, soundFile: 'r.mp3' },
    { ipa: 'w', word: 'wet', transcription: 'wet', bbcStart: 4367, soundFile: 'w.mp3' },
    { ipa: 'j', word: 'yes', transcription: 'jes', bbcStart: 4490, soundFile: 'j.mp3' },
  ],
};

const aliases: Record<string, string> = {
  'i': 'iː', 'u': 'uː', 'ɛ': 'e', 'ɑ': 'ɑː', 'ɔ': 'ɔː', 'oʊ': 'əʊ',
  'ɹ': 'r', 'ɹ̠': 'r', 'g': 'ɡ', 'ʧ': 'tʃ', 'ʤ': 'dʒ', 't͡ʃ': 'tʃ', 'd͡ʒ': 'dʒ',
  '∫': 'ʃ', 't∫': 'tʃ', 'η': 'ŋ',
};
const examples = Object.values(ipaGroups).flat();
export function ipaExample(symbol: string): IpaExample | undefined {
  const key = symbol.trim().replace(/:/g, 'ː');
  return examples.find(example => example.ipa === (aliases[key] ?? key));
}

export function ipaSoundUrl(example: IpaExample) {
  return new URL(example.soundFile, ieltsFighterAudio).href;
}

export function ipaWordUrl(example: IpaExample) {
  return example.wordAudio?.url ?? `${import.meta.env.BASE_URL}ipa/${example.word}.ogg`;
}

export const ipaAudioCredits: Partial<Record<string, { source: string; author: string; license: string; licenseUrl?: string }>> = audioCredits;
