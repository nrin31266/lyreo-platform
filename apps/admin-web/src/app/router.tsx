import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthCallbackPage, ProtectedRoute } from '@/app/auth';
import { AdminLayout } from '@/app/layout/AdminLayout';
import { CurriculumPage } from '@/pages/curriculum/CurriculumPage';
import { LexiconPage } from '@/pages/lexicon/LexiconPage';
import { OverviewPage } from '@/pages/overview/OverviewPage';
import { AiSettingsPage } from '@/pages/ai-settings/AiSettingsPage';
import { JobsPage } from '@/pages/jobs/JobsPage';
import { LessonBuilderPage } from '@/pages/lesson-builder/LessonBuilderPage';

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
        <Route path="lessons/new" element={<LessonBuilderPage />} />
        <Route path="jobs" element={<JobsPage />} />
        <Route path="settings/ai" element={<AiSettingsPage />} />
        <Route path="curriculum" element={<CurriculumPage />} />
        <Route path="lexicon" element={<LexiconPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
