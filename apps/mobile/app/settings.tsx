import { Link, Redirect } from 'expo-router';
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  View,
} from 'react-native';
import { mobileApi } from '../src/api';
import { useAuth } from '../src/auth';
import { colors, radius, spacing } from '../src/theme';

type DisplayTiming = 'OFF' | 'TAP_TO_SHOW' | 'AFTER_ATTEMPT' | 'ALWAYS';

type Preferences = {
  preferredAccent: string;
  translation: DisplayTiming;
  sentenceIpa: DisplayTiming;
  vocabularyNotes: DisplayTiming;
  grammarNotes: DisplayTiming;
  thoughtGroups: boolean;
  karaokeHighlighting: boolean;
  properNounHints: boolean;
  defaultPlaybackSpeed: number;
};

const defaults: Preferences = {
  preferredAccent: 'US',
  translation: 'AFTER_ATTEMPT',
  sentenceIpa: 'TAP_TO_SHOW',
  vocabularyNotes: 'AFTER_ATTEMPT',
  grammarNotes: 'AFTER_ATTEMPT',
  thoughtGroups: true,
  karaokeHighlighting: true,
  properNounHints: true,
  defaultPlaybackSpeed: 1,
};

const displayTimings: DisplayTiming[] = [
  'OFF',
  'TAP_TO_SHOW',
  'AFTER_ATTEMPT',
  'ALWAYS',
];

export default function SettingsScreen() {
  const auth = useAuth();
  const [preferences, setPreferences] = useState<Preferences>(defaults);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');

  useEffect(() => {
    if (!auth.authenticated) {
      setLoading(false);
      return;
    }

    auth.getAccessToken()
      .then(token => {
        if (!token) return;
        return mobileApi<Preferences>('/api/v1/learner/preferences', token)
          .then(setPreferences);
      })
      .catch(() => setMessage('Could not load preferences. Local defaults are shown.'))
      .finally(() => setLoading(false));
  }, [auth.authenticated, auth.getAccessToken]);

  if (!auth.loading && !auth.authenticated) {
    return <Redirect href="/login" />;
  }

  async function save() {
    const token = await auth.getAccessToken();
    if (!token) return;

    setSaving(true);
    setMessage('');
    try {
      const saved = await mobileApi<Preferences>('/api/v1/learner/preferences', token, {
        method: 'PUT',
        body: JSON.stringify(preferences),
      });
      setPreferences(saved);
      setMessage('Saved across your Lyreo devices.');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Could not save preferences.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <ScrollView contentContainerStyle={styles.page}>
      <Link href="/" style={styles.back}>← Home</Link>
      <Text style={styles.title}>Learning settings</Text>
      <Text style={styles.lead}>
        Persistent preferences sync across devices. Temporary playback speed, expanded notes
        and other one-session choices stay local to the current learning session.
      </Text>

      {loading ? (
        <ActivityIndicator />
      ) : (
        <>
          <Section title="Shadowing">
            <Toggle
              label="Thought groups"
              value={preferences.thoughtGroups}
              set={thoughtGroups => setPreferences({ ...preferences, thoughtGroups })}
            />
            <Toggle
              label="Karaoke highlighting"
              value={preferences.karaokeHighlighting}
              set={karaokeHighlighting => setPreferences({ ...preferences, karaokeHighlighting })}
            />
            <Choice
              label="Sentence IPA"
              value={preferences.sentenceIpa}
              values={displayTimings}
              onChange={sentenceIpa => setPreferences({ ...preferences, sentenceIpa })}
            />
          </Section>

          <Section title="Dictation">
            <Toggle
              label="Proper-noun hints"
              value={preferences.properNounHints}
              set={properNounHints => setPreferences({ ...preferences, properNounHints })}
            />
            <Choice
              label="Translation"
              value={preferences.translation}
              values={displayTimings}
              onChange={translation => setPreferences({ ...preferences, translation })}
            />
          </Section>

          <Section title="Vocabulary & grammar notes">
            <Choice
              label="Vocabulary notes"
              value={preferences.vocabularyNotes}
              values={displayTimings}
              onChange={vocabularyNotes => setPreferences({ ...preferences, vocabularyNotes })}
            />
            <Choice
              label="Grammar notes"
              value={preferences.grammarNotes}
              values={displayTimings}
              onChange={grammarNotes => setPreferences({ ...preferences, grammarNotes })}
            />
          </Section>

          <Pressable style={styles.primary} onPress={save} disabled={saving}>
            <Text style={styles.primaryText}>{saving ? 'Saving…' : 'Save preferences'}</Text>
          </Pressable>
          {message ? <Text style={styles.note}>{message}</Text> : null}
        </>
      )}

      <Pressable style={styles.signOut} onPress={auth.signOut}>
        <Text style={styles.signOutText}>Sign out</Text>
      </Pressable>
    </ScrollView>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.heading2}>{title}</Text>
      {children}
    </View>
  );
}

function Toggle({
  label,
  value,
  set,
}: {
  label: string;
  value: boolean;
  set: (value: boolean) => void;
}) {
  return (
    <View style={styles.toggle}>
      <Text style={styles.item}>{label}</Text>
      <Switch value={value} onValueChange={set} trackColor={{ true: colors.leaf }} />
    </View>
  );
}

function Choice({
  label,
  value,
  values,
  onChange,
}: {
  label: string;
  value: DisplayTiming;
  values: DisplayTiming[];
  onChange: (value: DisplayTiming) => void;
}) {
  return (
    <View style={styles.choice}>
      <Text style={styles.item}>{label}</Text>
      <View style={styles.chips}>
        {values.map(option => (
          <Pressable
            key={option}
            onPress={() => onChange(option)}
            style={[styles.chip, option === value && styles.chipActive]}
          >
            <Text style={[styles.chipText, option === value && styles.chipTextActive]}>
              {option.replaceAll('_', ' ')}
            </Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  page: {
    padding: spacing.lg,
    paddingTop: 54,
    paddingBottom: 50,
    gap: 16,
  },
  back: {
    color: colors.forest,
    fontWeight: '700',
  },
  title: {
    fontSize: 38,
    fontWeight: '800',
    letterSpacing: -1.4,
    color: colors.ink,
    marginTop: 18,
  },
  lead: {
    color: colors.inkMuted,
    lineHeight: 22,
  },
  section: {
    backgroundColor: colors.surface,
    padding: 20,
    borderRadius: radius.lg,
    borderColor: colors.border,
    borderWidth: 1,
  },
  heading2: {
    fontSize: 20,
    fontWeight: '800',
    color: colors.ink,
    marginBottom: 8,
  },
  toggle: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#EEF1EF',
  },
  item: {
    color: colors.ink,
    fontWeight: '600',
  },
  note: {
    color: colors.inkMuted,
    lineHeight: 19,
    fontSize: 12,
    marginTop: 4,
  },
  choice: {
    paddingVertical: 12,
    gap: 9,
    borderBottomWidth: 1,
    borderBottomColor: '#EEF1EF',
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
  },
  chip: {
    paddingHorizontal: 9,
    paddingVertical: 7,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.border,
  },
  chipActive: {
    backgroundColor: colors.forest,
    borderColor: colors.forest,
  },
  chipText: {
    fontSize: 10,
    fontWeight: '700',
    color: colors.inkMuted,
  },
  chipTextActive: {
    color: colors.surface,
  },
  primary: {
    backgroundColor: colors.forest,
    borderRadius: radius.lg,
    padding: 16,
    alignItems: 'center',
  },
  primaryText: {
    color: colors.surface,
    fontWeight: '800',
  },
  signOut: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.lg,
    padding: 16,
    alignItems: 'center',
    marginTop: 8,
  },
  signOutText: {
    color: colors.ink,
    fontWeight: '800',
  },
});
