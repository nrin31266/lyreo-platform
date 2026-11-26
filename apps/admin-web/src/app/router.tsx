import { lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthCallbackPage, ProtectedRoute } from '@/app/auth';
import { AdminLayout } from '@/app/layout/AdminLayout';
import { CurriculumPage } from '@/pages/curriculum/CurriculumPage';
import { OverviewPage } from '@/pages/overview/OverviewPage';

const AdminSettingsPage = lazy(() =>
  import('@/pages/settings/AdminSettingsPage').then((module) => ({
    default: module.AdminSettingsPage,
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

const OrganizationPage = lazy(() =>
  import('@/pages/lessons/OrganizationPage').then((m) => ({ default: m.OrganizationPage })),
);
const LearnersPage = lazy(() =>
  import('@/pages/learners/LearnersPage').then((m) => ({ default: m.LearnersPage })),
);
const LearnerDetailPage = lazy(() =>
  import('@/pages/learners/LearnersPage').then((m) => ({ default: m.LearnerDetailPage })),
);
const PlansPage = lazy(() =>
  import('@/pages/plans/PlansPage').then((m) => ({ default: m.PlansPage })),
);
const RewardsHubPage = lazy(() =>
  import('@/pages/rewards/RewardsHubPage').then((m) => ({ default: m.RewardsHubPage })),
);
const NotificationsPage = lazy(() =>
  import('@/pages/notifications/NotificationsPage').then((m) => ({ default: m.NotificationsPage })),
);
const VocabularyPage = lazy(() =>
  import('@/pages/vocabulary/VocabularyPage').then((m) => ({ default: m.VocabularyPage })),
);
const LexiconPage = lazy(() =>
  import('@/pages/lexicon/LexiconPage').then((m) => ({ default: m.LexiconPage })),
);
const LexiconDetailPage = lazy(() =>
  import('@/pages/lexicon/LexiconDetailPage').then((m) => ({ default: m.LexiconDetailPage })),
);
const PhoneticsPage = lazy(() =>
  import('@/pages/phonetics/PhoneticsPage').then((m) => ({ default: m.PhoneticsPage })),
);
const GrammarPage = lazy(() =>
  import('@/pages/grammar/GrammarPage').then((m) => ({ default: m.GrammarPage })),
);
const GrammarDetailPage = lazy(() =>
  import('@/pages/grammar/GrammarPage').then((m) => ({ default: m.GrammarDetailPage })),
);
const QuestionDetailPage = lazy(() =>
  import('@/pages/library/QuestionEditor').then((m) => ({ default: m.QuestionDetailPage })),
);
const ToeicPage = lazy(() =>
  import('@/pages/toeic/ToeicPage').then((m) => ({ default: m.ToeicPage })),
);
const ToeicDetailPage = lazy(() =>
  import('@/pages/toeic/ToeicPage').then((m) => ({ default: m.ToeicDetailPage })),
);
const LessonDetailPage = lazy(() =>
  import('@/pages/lessons/LessonDetailPage').then((module) => ({
    default: module.LessonDetailPage,
  })),
);
const LessonsPage = lazy(() =>
  import('@/pages/lessons/LessonsPage').then((module) => ({
    default: module.LessonsPage,
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
        <Route path="lessons" element={<LessonsPage />} />
        <Route path="lessons/organization" element={<OrganizationPage />} />
        <Route
          path="lessons/categories"
          element={<Navigate to="/lessons/organization?tab=categories" replace />}
        />
        <Route
          path="lessons/channels"
          element={<Navigate to="/lessons/organization?tab=channels" replace />}
        />
        <Route path="lessons/new" element={<LessonBuilderPage />} />
        <Route path="lessons/:lessonId" element={<LessonDetailPage />} />
        <Route path="jobs" element={<JobsPage />} />
        <Route path="learners" element={<LearnersPage />} />
        <Route path="learners/progress" element={<Navigate to="/learners" replace />} />
        <Route path="learners/:learnerId" element={<LearnerDetailPage />} />
        <Route path="plans" element={<PlansPage />} />
        <Route path="rewards" element={<RewardsHubPage />} />
        <Route path="missions" element={<Navigate to="/rewards?tab=missions" replace />} />
        <Route path="achievements" element={<Navigate to="/rewards?tab=achievements" replace />} />
        <Route path="notifications" element={<NotificationsPage />} />
        <Route path="settings" element={<AdminSettingsPage />} />
        <Route path="settings/ai" element={<Navigate to="/settings?tab=ai" replace />} />
        <Route path="curriculum" element={<CurriculumPage />} />
        <Route path="vocabulary" element={<VocabularyPage />} />
        <Route path="lexicon" element={<LexiconPage />} />
        <Route path="lexicon/:id" element={<LexiconDetailPage />} />
        <Route path="phonetics" element={<PhoneticsPage />} />
        <Route path="grammar" element={<GrammarPage />} />
        <Route path="grammar/:kind" element={<GrammarPage />} />
        <Route path="grammar/questions/:id" element={<QuestionDetailPage />} />
        <Route path="grammar/:kind/:id" element={<GrammarDetailPage />} />
        <Route path="toeic" element={<ToeicPage />} />
        <Route path="toeic/:id" element={<ToeicDetailPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
