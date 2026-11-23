import { lazy } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { AuthCallbackPage, ProtectedRoute } from "@/app/auth";
import { AdminLayout } from "@/app/layout/AdminLayout";
import { CurriculumPage } from "@/pages/curriculum/CurriculumPage";
import { OverviewPage } from "@/pages/overview/OverviewPage";

const AiSettingsPage = lazy(() =>
  import("@/pages/ai-settings/AiSettingsPage").then((module) => ({
    default: module.AiSettingsPage,
  })),
);

const JobsPage = lazy(() =>
  import("@/pages/jobs/JobsPage").then((module) => ({
    default: module.JobsPage,
  })),
);

const LessonBuilderPage = lazy(() =>
  import("@/pages/lesson-builder/LessonBuilderPage").then((module) => ({
    default: module.LessonBuilderPage,
  })),
);

const OrganizationPage = lazy(() => import("@/pages/lessons/OrganizationPage").then(m => ({default: m.OrganizationPage})));
const LearnersPage = lazy(() => import("@/pages/learners/LearnersPage").then(m => ({ default: m.LearnersPage })));
const LearnerDetailPage = lazy(() => import("@/pages/learners/LearnersPage").then(m => ({ default: m.LearnerDetailPage })));
const LexiconPage = lazy(() => import("@/pages/lexicon/LexiconPage").then(m => ({ default: m.LexiconPage })));
const LexiconDetailPage = lazy(() => import("@/pages/lexicon/LexiconDetailPage").then(m => ({ default: m.LexiconDetailPage })));
const GrammarPage = lazy(() => import("@/pages/grammar/GrammarPage").then(m => ({ default: m.GrammarPage })));
const GrammarDetailPage = lazy(() => import("@/pages/grammar/GrammarPage").then(m => ({ default: m.GrammarDetailPage })));
const QuestionDetailPage = lazy(() => import("@/pages/library/QuestionEditor").then(m => ({ default: m.QuestionDetailPage })));
const ToeicPage = lazy(() => import("@/pages/toeic/ToeicPage").then(m => ({ default: m.ToeicPage })));
const ToeicDetailPage = lazy(() => import("@/pages/toeic/ToeicPage").then(m => ({ default: m.ToeicDetailPage })));
const LessonDetailPage = lazy(() =>
  import("@/pages/lessons/LessonDetailPage").then((m) => ({
    default: m.LessonDetailPage,
  })),
);
const LessonsPage = lazy(() =>
  import("@/pages/lessons/LessonsPage").then((m) => ({
    default: m.LessonsPage,
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
        <Route path="lessons/categories" element={<Navigate to="/lessons/organization?tab=categories" replace />} />
        <Route path="lessons/channels" element={<Navigate to="/lessons/organization?tab=channels" replace />} />
        <Route path="lessons/new" element={<LessonBuilderPage />} />
        <Route path="lessons/:lessonId" element={<LessonDetailPage />} />
        <Route path="jobs" element={<JobsPage />} />
        <Route path="learners" element={<LearnersPage />} />
        <Route path="learners/:learnerId" element={<LearnerDetailPage />} />
        <Route path="settings/ai" element={<AiSettingsPage />} />
        <Route path="curriculum" element={<CurriculumPage />} />
        <Route path="lexicon" element={<LexiconPage />} />
        <Route path="lexicon/:id" element={<LexiconDetailPage />} />
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
