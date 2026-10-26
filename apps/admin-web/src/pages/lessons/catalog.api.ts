import { api } from "@/lib/api";
import { z } from "zod";
export const categorySchema = z.object({
  id: z.string(),
  name: z.string(),
  lessonCount: z.number(),
});
export type Category = z.infer<typeof categorySchema>;
export type Channel = {
  id: string;
  title: string;
  lessonCount: number;
  avatarUrl: string | null;
  syncedAt: string | null;
  syncError: string | null;
  syncJobId: string | null;
  syncStatus: string | null;
};
export type LessonItem = {
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
export const saveMetadata = (id: string, payload: Metadata) =>
  api<void>(`${base}/lessons/${id}/metadata`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
export const saveCategory = (name: string, id?: string) =>
  api<Category>(`${base}/lesson-categories${id ? `/${id}` : ""}`, {
    method: id ? "PUT" : "POST",
    body: JSON.stringify({ name }),
  });
export const deleteCategory = (id: string) =>
  api<void>(`${base}/lesson-categories/${id}`, { method: "DELETE" });
export const deleteDraft = (id: string) =>
  api<void>(`${base}/lessons/${id}`, { method: "DELETE" });
export const setVisibility = (id: string, visible: boolean) =>
  api<void>(`${base}/lessons/${id}/visibility`, {
    method: "PUT",
    body: JSON.stringify({ visible }),
  });
export const syncChannels = (channelIds: string[]) =>
  api<{ jobId: string }>(`${base}/lesson-channels/sync`, {
    method: "POST",
    body: JSON.stringify({ channelIds }),
  });
