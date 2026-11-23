import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { avatarPortraitSource } from './learner-avatar-presets';
import { fetchLearnerAvatar, type LearnerEntry } from './learners.api';
import './learner-avatar.css';

const avatarColors = ['rose', 'peach', 'yellow', 'mint', 'sky', 'lilac'] as const;

export function learnerAvatarColor(id: string) {
  let hash = 0;
  for (const character of id) hash = (Math.imul(hash, 31) + character.charCodeAt(0)) >>> 0;
  return avatarColors[hash % avatarColors.length] ?? avatarColors[0];
}

export function learnerInitials(name: string | null, email: string | null) {
  const source = (name?.trim() || email?.split('@')[0] || '').normalize('NFC');
  const [first, ...rest] = source.match(/[\p{L}\p{N}][\p{L}\p{M}\p{N}]*/gu) ?? [];
  if (!first) return '?';
  const last = rest.at(-1);
  const initials = last ? [Array.from(first)[0], Array.from(last)[0]] : Array.from(first).slice(0, 2);
  return Array.from(initials.join('').toUpperCase()).slice(0, 2).join('');
}

function UploadedAvatar({ learnerId, uploadId }: { learnerId: string; uploadId: string }) {
  const image = useQuery({
    queryKey: ['learner-avatar', learnerId, uploadId],
    queryFn: ({ signal }) => fetchLearnerAvatar(learnerId, signal),
    retry: false,
    staleTime: Infinity,
  });
  const [url, setUrl] = useState<string>();
  useEffect(() => {
    if (!image.data) return;
    const objectUrl = URL.createObjectURL(image.data);
    setUrl(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [image.data]);
  return url ? <AvatarImage src={url} alt="" /> : null;
}

export function LearnerAvatar({ entry, size }: { entry: LearnerEntry; size?: 'sm' | 'lg' }) {
  const portrait = entry.avatarPreset ? avatarPortraitSource(entry.avatarPreset) : undefined;
  return (
    <Avatar size={size} aria-hidden="true" className="after:border-0">
      {portrait && <AvatarImage src={portrait} alt="" />}
      {entry.avatarUploadId && (
        <UploadedAvatar key={entry.avatarUploadId} learnerId={entry.learnerId} uploadId={entry.avatarUploadId} />
      )}
      <AvatarFallback className="learner-avatar-initials" data-color={learnerAvatarColor(entry.learnerId)}>
        {learnerInitials(entry.displayName, entry.email)}
      </AvatarFallback>
    </Avatar>
  );
}
