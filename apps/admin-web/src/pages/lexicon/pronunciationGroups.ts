import type { Sound } from "../library/library.types";
export function pronunciationGroups(headword: string, sounds: Sound[]) {
  const groups = new Map<string, Sound[]>();
  for (const sound of sounds) {
    const c = sound.pronunciationContext;
    const scope = c?.scope_status ?? "UNSPECIFIED";
    const key = JSON.stringify([c?.form ?? headword, sound.ipa || c?.source_record_key || sound.id, c?.notation_system ?? "SOURCE", c?.usage, scope, scope === "EXPLICIT" ? sound.itemId : null, c?.regions]);
    const rows = groups.get(key) ?? [];
    const identity = sound.assetId ?? c?.source_record_key ?? sound.id;
    if (!rows.some(row => (row.assetId ?? row.pronunciationContext?.source_record_key ?? row.id) === identity)) rows.push(sound);
    groups.set(key, rows);
  }
  return [...groups.values()];
}
