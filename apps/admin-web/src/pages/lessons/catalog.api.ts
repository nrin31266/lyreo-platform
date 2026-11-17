import { api } from "@/lib/api";
import { z } from "zod";
export const categorySchema = z.object({
  id: z.string(),
  name: z.string(),
  lessonCount: z.number(),
  translations: z.record(z.string(), z.string()).optional(),
});
export type Category = z.infer<typeof categorySchema>;
export const categoryName = (category: Category, locale: string) => category.translations?.[locale] || category.translations?.[locale.split('-')[0]] || category.name;
export type Channel = {
  id: string;
  title: string;
  lessonCount: number;
  linkedLessonCount: number;
  avatarUrl: string | null;
  syncedAt: string | null;
  syncError: string | null;
  syncJobId: string | null;
  syncStatus: string | null;
  visible: boolean;
};
export type LessonItem = {
  version?: number; revision?: number; deleted?: boolean; cefr?: string; activities?: string[]; processing?: boolean; aiFailed?: boolean;
  id: string;
  title: string;
  sourceType: string;
  status: string;
  startMs: number | null;
  endMs: number | null;
  channelId: string | null;
  channelTitle: string | null;
  updatedAt: string;
  authoring: boolean;
  categories: Category[];
  description: string;
  imageUrl: string | null;
  visible: boolean;
  channelVisible: boolean;
  effectiveVisible: boolean;
};
export type LessonPage = {
  items: LessonItem[];
  total: number;
  page: number;
  size: number;
};
export const imageUrlSchema = z
  .string()
  .max(2048)
  .refine((value) => {
    if (!value) return true;
    try {
      const url = new URL(value);
      return (
        ["http:", "https:"].includes(url.protocol) &&
        !url.username &&
        !url.password
      );
    } catch {
      return false;
    }
  }, "Enter an HTTP(S) image URL");
export const metadataSchema = z.object({
  title: z.string().trim().min(1).max(500),
  description: z.string().max(5000),
  imageUrl: imageUrlSchema,
  categoryIds: z.array(z.string()).max(100),
});
export const createMetadataSchema = metadataSchema.extend({ title: z.string().trim().max(500) });
export type Metadata = z.infer<typeof metadataSchema>;
const base = "/api/v1/admin";
export const fetchLessons = (params: URLSearchParams, signal?: AbortSignal) =>
  api<LessonPage>(`${base}/lessons?${params}`, { signal });
export const fetchMetadata = (id: string, signal?: AbortSignal) =>
  api<LessonItem>(`${base}/lessons/${id}/metadata`, { signal });
export const fetchCategories = (signal?: AbortSignal) =>
  api<Category[]>(`${base}/lesson-categories`, { signal });
export const fetchChannels = (signal?: AbortSignal) =>
  api<Channel[]>(`${base}/lesson-channels`, { signal });
export const saveMetadata = (id: string, payload: Metadata & {version?: number}) =>
  api<void>(`${base}/lessons/${id}/metadata`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
export const saveCategory = (name: string, id?: string, translations?: Record<string, string>) =>
  api<Category>(`${base}/lesson-categories${id ? `/${id}` : ""}`, {
    method: id ? "PUT" : "POST",
    body: JSON.stringify({ name, translations }),
  });
export const deleteCategory = (id: string) =>
  api<void>(`${base}/lesson-categories/${id}`, { method: "DELETE" });
export const deleteDraft = (id: string, version?: number) =>
  api<void>(`${base}/lessons/${id}?version=${version ?? 0}`, { method: "DELETE" });
export const setVisibility = (id: string, visible: boolean, version?: number) =>
  api<void>(`${base}/lessons/${id}/visibility`, {
    method: "PUT",
    body: JSON.stringify({ visible, version: version ?? 0 }),
  });
export const syncChannels = (channelIds: string[]) =>
  api<{ jobId: string }>(`${base}/lesson-channels/sync`, {
    method: "POST",
    body: JSON.stringify({ channelIds }),
  });

export const fetchSyncConfiguration = (signal?: AbortSignal) => api<{ configured: boolean }>(`${base}/lesson-channels/sync-configuration`, { signal });
export const setChannelVisibility = (id: string, visible: boolean) => api<void>(`${base}/lesson-channels/${encodeURIComponent(id)}/visibility`, { method: 'PUT', body: JSON.stringify({ visible }) });
export const deleteChannel = (id: string) => api<void>(`${base}/lesson-channels/${encodeURIComponent(id)}`, { method: 'DELETE' });

export const restoreLesson = (id: string, version?: number) => api<void>(`${base}/lessons/${id}/restore`, {method: "POST",body: JSON.stringify({version: version ?? 0})});
