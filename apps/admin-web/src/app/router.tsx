import { lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthCallbackPage, ProtectedRoute } from '@/app/auth';
import { AdminLayout } from '@/app/layout/AdminLayout';
import { CurriculumPage } from '@/pages/curriculum/CurriculumPage';
import { LexiconPage } from '@/pages/lexicon/LexiconPage';
import { OverviewPage } from '@/pages/overview/OverviewPage';

const AiSettingsPage = lazy(() =>
  import('@/pages/ai-settings/AiSettingsPage').then((module) => ({
    default: module.AiSettingsPage,
  })),
);

const JobsPage = lazy(() =>
  import('@/pages/jobs/JobsPage').then((module) => ({
    default: module.JobsPage,
  })),
);

const LessonBuilderPage = lazy(() =>
  import('@/pages/lesson-builder/LessonBuilderPage').then((module) => ({
    default: module.LessonBuilderPage,
  })),
);

export function AppRouter() {
  return (
    <Routes>
      <Route path="/auth/callback" element={<AuthCallbackPage />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <AdminLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<OverviewPage />} />
        <Route
          path="lessons"
          element={<Navigate to="/lessons/new" replace />}
        />
        <Route path="lessons/new" element={<LessonBuilderPage />} />
        <Route path="lessons/:lessonId" element={<LessonBuilderPage />} />
        <Route path="jobs" element={<JobsPage />} />
        <Route path="settings/ai" element={<AiSettingsPage />} />
        <Route path="curriculum" element={<CurriculumPage />} />
        <Route path="lexicon" element={<LexiconPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
