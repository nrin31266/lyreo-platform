import type { LexiconItem, Sound } from "../library/library.types";

const sourceRegions = new Map([
  ["general american", "US"], ["received pronunciation", "UK"],
  ["southern england", "Southern-England"], ["miền nam nước anh", "Southern-England"],
  ["northern england", "Northern-England"], ["scotland", "Scotland"], ["wales", "Wales"],
  ["california", "California"], ["úc", "AU"],
]);

export function pronunciationRegions(sound: Sound) {
  const context = sound.pronunciationContext;
  const regions = context?.regions?.length ? context.regions : (sound.accent ?? "").split("/")
    .filter(value => value && value !== "unspecified");
  const inferred = (context?.labels ?? []).flatMap(label => {
    const region = sourceRegions.get(label.trim().toLowerCase().replace(/[-_\s]+/g, " "));
    return region ? [region] : [];
  });
  return [...new Set([...regions, ...inferred])];
}

export function pronunciationScope(sound: Sound, items: LexiconItem[]) {
  const item = items.find(row => row.id === sound.itemId);
  if (!item) return null;
  const pos = item.partOfSpeech === "adj" ? "adjective" : item.partOfSpeech === "adv" ? "adverb" : item.partOfSpeech;
  const labelledRecording = sound.pronunciationContext?.source === "enwiktionary"
    && sound.audioFile?.replace(/\.[a-z0-9]+$/i, "").toLowerCase().endsWith(` (${pos})`);
  return sound.pronunciationContext?.scope_status === "EXPLICIT" || labelledRecording ? item : null;
}

export function pronunciationGroups(headword: string, sounds: Sound[], items: LexiconItem[] = []) {
  const groups = new Map<string, Sound[]>();
  for (const sound of sounds) {
    const context = sound.pronunciationContext;
    const notation = sound.ipa?.trim();
    const scope = pronunciationScope(sound, items)?.partOfSpeech
      ?? (context?.scope_status === "EXPLICIT" ? sound.itemId : null);
    const identity = sound.assetId ?? sound.audioUrl ?? context?.source_record_key ?? sound.id;
    const key = JSON.stringify([context?.form || headword, notation || identity, context?.notation_system ?? "SOURCE", context?.usage || null, scope]);
    const records = groups.get(key) ?? [];
    const repeated = records.some(row => notation && !row.assetId && !row.audioUrl && !sound.assetId && !sound.audioUrl
      ? JSON.stringify(pronunciationRegions(row)) === JSON.stringify(pronunciationRegions(sound))
      : (row.assetId ?? row.audioUrl ?? row.pronunciationContext?.source_record_key ?? row.id) === identity);
    if (!repeated) records.push(sound);
    groups.set(key, records);
  }
  return [...groups.values()];
}
