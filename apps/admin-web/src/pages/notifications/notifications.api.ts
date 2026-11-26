import { api } from '@/lib/api';

export const notificationsKey = ['admin-notifications'] as const;

export type NotificationCategory = 'SYSTEM' | 'STUDY_REMINDER' | 'REWARD' | 'PROMO';
export type NotificationAudience = 'ALL' | 'FREE' | 'PLUS';
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
};

export type SaveNotificationInput = {
  title: string;
  body: string;
  category: NotificationCategory;
  targetAudience: NotificationAudience;
  status: NotificationStatus;
  publishedAt?: string | null;
  expiresAt?: string | null;
};

const base = '/api/v1/admin/notifications';

export function fetchAdminNotifications(signal?: AbortSignal) {
  return api<LearnerNotification[]>(base, { signal });
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
