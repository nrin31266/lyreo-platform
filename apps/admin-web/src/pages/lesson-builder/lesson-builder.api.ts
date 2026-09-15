import { api } from '@/lib/api';
import type { BuildAccepted, BuildLessonPayload } from './lesson-builder.types';

export async function requestLessonBuild(payload: BuildLessonPayload): Promise<BuildAccepted> {
  return api<BuildAccepted>('/api/v1/admin/lessons/build', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}
