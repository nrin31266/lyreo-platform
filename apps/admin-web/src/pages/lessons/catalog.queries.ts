import { useQuery } from "@tanstack/react-query";
import * as catalog from "./catalog.api";
export const catalogKeys = {
  all: ["catalog"] as const,
  lessons: (query: string) => ["catalog", "lessons", query] as const,
  categories: ["catalog", "categories"] as const,
  channels: ["catalog", "channels"] as const,
  metadata: (id: string) => ["catalog", "metadata", id] as const,
};
export function useCategories() {
  return useQuery({
    queryKey: catalogKeys.categories,
    queryFn: ({ signal }) => catalog.fetchCategories(signal),
  });
}
export function useMetadata(id: string) {
  return useQuery({
    queryKey: catalogKeys.metadata(id),
    queryFn: ({ signal }) => catalog.fetchMetadata(id, signal),
  });
}
