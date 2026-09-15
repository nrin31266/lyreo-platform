export type SourceType = 'TEXT' | 'AUDIO' | 'YOUTUBE';

export type BuildAccepted = {
  lessonId: string;
  jobId: string;
};

export type BuildLessonPayload = {
  title: string;
  sourceType: SourceType;
  sourceText: string | null;
  sourceReference: string | null;
  activities: string[];
  annotations: string[];
  accent: string;
  pronunciationStrategy: string;
};
