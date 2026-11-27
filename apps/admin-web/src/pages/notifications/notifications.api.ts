import { api } from '@/lib/api';

export const notificationsKey = ['admin-notifications'] as const;

export type NotificationCategory = 'SYSTEM' | 'STUDY_REMINDER' | 'REWARD' | 'PROMO';
export type NotificationAudience = 'ALL' | 'FREE' | 'PLUS' | 'SELECTED';
export type NotificationStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export type LearnerNotification = {
  id: string;
  title: string;
  body: string;
  category: NotificationCategory;
  targetAudience: NotificationAudience;
  status: NotificationStatus;
  publishedAt: string | null;
  expiresAt: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  recipientIds: string[];
  recipientLabels: string[];
};

export type SaveNotificationInput = {
  title: string;
  body: string;
  category: NotificationCategory;
  targetAudience?: NotificationAudience;
  status: NotificationStatus;
  recipientIds: string[];
  publishedAt?: string | null;
  expiresAt?: string | null;
};

export const activityCategories = ['MEMBERSHIP', 'ACCOUNT', 'NOTIFICATION', 'REWARD', 'OTHER'] as const;
export type ActivityCategory = (typeof activityCategories)[number];
type AdminActivity = {
  id: string;
  category: ActivityCategory;
  action: string;
  learnerId: string | null;
  learnerEmail: string | null;
  actor: string | null;
  detail: string | null;
  amount: number | null;
  occurredAt: string;
  scheduledFor: string | null;
};

const base = '/api/v1/admin/notifications';

export function fetchAdminNotifications(signal?: AbortSignal) {
  return api<LearnerNotification[]>(base, { signal });
}

export function fetchAdminActivity(category: ActivityCategory | 'ALL', page: number, signal?: AbortSignal) {
  const params = new URLSearchParams({ page: String(page) });
  if (category !== 'ALL') params.set('category', category);
  return api<{ items: AdminActivity[]; page: number; hasNext: boolean }>(`${base}/activity?${params}`, { signal });
}

export function createAdminNotification(body: SaveNotificationInput) {
  return api<LearnerNotification>(base, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function updateAdminNotification(id: string, body: SaveNotificationInput) {
  return api<LearnerNotification>(`${base}/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export function deleteAdminNotification(id: string) {
  return api<void>(`${base}/${encodeURIComponent(id)}`, { method: 'DELETE' });
}
