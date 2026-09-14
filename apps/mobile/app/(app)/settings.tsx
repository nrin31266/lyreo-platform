import type { ThemePreference } from '@lyreo/design-system';
import { supportedLocales, type SupportedLocale } from '@lyreo/i18n';
import { Link } from 'expo-router';
import type { ReactNode } from 'react';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, Switch, View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { useApiClient } from '@/api/api-provider';
import { useSession } from '@/auth/use-session';
import { ErrorState } from '@/components/states/error-state';
import { LoadingState } from '@/components/states/loading-state';
import { Button } from '@/components/ui/button';
import { Text } from '@/components/ui/text';
import {
  defaultLearnerPreferences,
  getLearnerPreferences,
  updateLearnerPreferences,
  type DisplayTiming,
  type LearnerPreferences,
} from '@/features/preferences/api';
import { useAppLocale } from '@/providers/LocaleProvider';
import { useAppTheme } from '@/providers/AppThemeProvider';

const displayTimings: DisplayTiming[] = ['OFF', 'TAP_TO_SHOW', 'AFTER_ATTEMPT', 'ALWAYS'];
const themePreferences: ThemePreference[] = ['system', 'light', 'dark'];

export default function SettingsScreen() {
  const client = useApiClient();
  const session = useSession();
  const { colors, preference: themePreference, setPreference: setThemePreference } = useAppTheme();
  const { locale, setLocale } = useAppLocale();
  const { t } = useTranslation(['mobile', 'common']);
  const [preferences, setPreferences] = useState<LearnerPreferences>(defaultLearnerPreferences);
  const [loadState, setLoadState] = useState<'loading' | 'loaded' | 'error'>('loading');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setLoadState('loading');
    setMessage(null);
    void getLearnerPreferences(client, signal)
      .then(value => {
        setPreferences(value);
        setLoadState('loaded');
      })
      .catch(() => {
        if (signal?.aborted) return;
        setLoadState('error');
      });
  }, [client]);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  async function save() {
    setSaving(true);
    setMessage(null);
    try {
      const saved = await updateLearnerPreferences(client, preferences);
      setPreferences(saved);
      setMessage({ text: t('mobile:settings.saved'), error: false });
    } catch {
      setMessage({ text: t('mobile:settings.saveFailed'), error: true });
    } finally {
      setSaving(false);
    }
  }

  return (
    <ScrollView className="flex-1 bg-background" contentContainerStyle={{ padding: 24, paddingTop: 54, paddingBottom: 50, gap: 16 }}>
      <Link href="/" asChild>
        <Pressable><Text className="font-bold text-primary">{t('mobile:settings.back')}</Text></Pressable>
      </Link>
      <Text className="mt-[18px] text-[40px] font-extrabold tracking-[-1.5px] text-foreground">{t('mobile:settings.title')}</Text>
      <Text className="leading-[22px] text-muted-foreground">{t('mobile:settings.lead')}</Text>

      <Section title={t('mobile:settings.appearance')}>
        <Text className="mb-2 text-xs leading-[18px] text-muted-foreground">{t('mobile:settings.localPreferenceNote')}</Text>
        <ChoiceChips<SupportedLocale>
          label={t('mobile:settings.language')}
          value={locale}
          values={supportedLocales}
          renderLabel={value => t(`common:language.${value === 'en' ? 'english' : 'vietnamese'}`)}
          onChange={value => void setLocale(value)}
        />
        <ChoiceChips<ThemePreference>
          label={t('mobile:settings.theme')}
          value={themePreference}
          values={themePreferences}
          renderLabel={value => t(`common:theme.${value}`)}
          onChange={value => void setThemePreference(value)}
        />
      </Section>

      {loadState === 'loading' ? <LoadingState /> : null}
      {loadState === 'error' ? (
        <ErrorState message={t('mobile:settings.loadFailed')} onRetry={() => load()} />
      ) : null}
      {loadState === 'loaded' ? (
        <>
          <Section title={t('mobile:settings.shadowing')}>
            <Toggle
              label={t('mobile:settings.thoughtGroups')}
              value={preferences.thoughtGroups}
              set={thoughtGroups => setPreferences({ ...preferences, thoughtGroups })}
            />
            <Toggle
              label={t('mobile:settings.karaoke')}
              value={preferences.karaokeHighlighting}
              set={karaokeHighlighting => setPreferences({ ...preferences, karaokeHighlighting })}
            />
            <Choice
              label={t('mobile:settings.sentenceIpa')}
              value={preferences.sentenceIpa}
              onChange={sentenceIpa => setPreferences({ ...preferences, sentenceIpa })}
            />
          </Section>

          <Section title={t('mobile:settings.dictation')}>
            <Toggle
              label={t('mobile:settings.properNounHints')}
              value={preferences.properNounHints}
              set={properNounHints => setPreferences({ ...preferences, properNounHints })}
            />
            <Choice
              label={t('mobile:settings.translation')}
              value={preferences.translation}
              onChange={translation => setPreferences({ ...preferences, translation })}
            />
          </Section>

          <Section title={t('mobile:settings.vocabularyGrammar')}>
            <Choice
              label={t('mobile:settings.vocabularyNotes')}
              value={preferences.vocabularyNotes}
              onChange={vocabularyNotes => setPreferences({ ...preferences, vocabularyNotes })}
            />
            <Choice
              label={t('mobile:settings.grammarNotes')}
              value={preferences.grammarNotes}
              onChange={grammarNotes => setPreferences({ ...preferences, grammarNotes })}
            />
          </Section>

          <Button size="lg" onPress={() => void save()} disabled={saving}>
            {saving ? t('common:status.saving') : t('mobile:settings.save')}
          </Button>
          {message ? (
            <Text className={message.error ? 'text-sm text-destructive' : 'text-sm text-success'}>
              {message.text}
            </Text>
          ) : null}
        </>
      ) : null}

      <Button variant="outline" size="lg" onPress={() => void session.signOut()}>
        {t('common:actions.signOut')}
      </Button>
    </ScrollView>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <View className="rounded-lg border border-border bg-surface p-5">
      <Text className="mb-2 text-xl font-extrabold text-foreground">{title}</Text>
      {children}
    </View>
  );
}

function Toggle({ label, value, set }: { label: string; value: boolean; set: (value: boolean) => void }) {
  const { colors } = useAppTheme();
  return (
    <View className="flex-row items-center justify-between border-b border-border py-2.5">
      <Text className="font-semibold text-foreground">{label}</Text>
      <Switch
        value={value}
        onValueChange={set}
        trackColor={{ false: colors.input, true: colors.primary }}
        thumbColor={value ? colors.primaryForeground : colors.surface}
      />
    </View>
  );
}

function Choice({ label, value, onChange }: { label: string; value: DisplayTiming; onChange: (value: DisplayTiming) => void }) {
  const { t } = useTranslation('mobile');
  return (
    <ChoiceChips
      label={label}
      value={value}
      values={displayTimings}
      renderLabel={option => t(`settings.displayTiming.${option}`)}
      onChange={onChange}
    />
  );
}

function ChoiceChips<T extends string>({
  label,
  value,
  values,
  renderLabel,
  onChange,
}: {
  label: string;
  value: T;
  values: readonly T[];
  renderLabel: (value: T) => string;
  onChange: (value: T) => void;
}) {
  return (
    <View className="gap-2 border-b border-border py-3 last:border-b-0">
      <Text className="font-semibold text-foreground">{label}</Text>
      <View className="flex-row flex-wrap gap-1.5">
        {values.map(option => {
          const active = option === value;
          return (
            <Pressable
              key={option}
              onPress={() => onChange(option)}
              className={active
                ? 'rounded-full border border-primary bg-primary px-2.5 py-2'
                : 'rounded-full border border-border bg-surface px-2.5 py-2'}
            >
              <Text className={active
                ? 'text-[11px] font-bold text-primary-foreground'
                : 'text-[11px] font-bold text-muted-foreground'}
              >
                {renderLabel(option)}
              </Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}
