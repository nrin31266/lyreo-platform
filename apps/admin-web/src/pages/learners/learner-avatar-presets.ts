export const portraitPacks = {
  animals: ['RED_PANDA', 'PANDA', 'KOALA', 'LION', 'BEAR', 'PENGUIN', 'GIRAFFE', 'ORCA', 'TOUCAN'],
  flowers: ['FLOWER_HEATHER', 'FLOWER_CLIMBING_LILY', 'FLOWER_CHRYSANTHEMUMS', 'FLOWER_ROSES_LILACS', 'FLOWER_IRISES', 'FLOWER_POTTED_PANSIES'],
  characters: ['PEEP_CURLS', 'PEEP_BEANIE', 'PEEP_BUN', 'PEEP_HEADSCARF', 'PEEP_LONG_HAIR', 'PEEP_QUIFF'],
  comics: ['COMIC_MILO', 'COMIC_LUNA', 'COMIC_NOVA', 'COMIC_KYO', 'COMIC_MIKA', 'COMIC_ARI'],
} as const;

type AvatarPack = keyof typeof portraitPacks;
export type AvatarPreset = (typeof portraitPacks)[AvatarPack][number] | 'CAT' | 'DOG' | 'FOX' | 'RABBIT';

export const avatarPackIds = Object.keys(portraitPacks) as AvatarPack[];
const images = import.meta.glob<string>('../../assets/learner-avatars/portrait-*.webp', {
  eager: true,
  import: 'default',
  query: '?url',
});

export function avatarPortraitSource(preset: AvatarPreset): string | undefined {
  const file = preset.toLowerCase().replaceAll('_', '-');
  return images[`../../assets/learner-avatars/portrait-${file}.webp`];
}
