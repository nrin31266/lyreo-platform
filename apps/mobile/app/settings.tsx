import { supportedLocales, type SupportedLocale } from '@lyreo/i18n';
import type { ThemePreference } from '@lyreo/design-system';
import { Link, Redirect } from 'expo-router';
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, Switch, View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { mobileApi } from '../src/api';
import { useAuth } from '../src/auth';
import { Button } from '../src/components/ui/button';
import { Text } from '../src/components/ui/text';
import { useAppLocale } from '../src/providers/LocaleProvider';
import { useAppTheme } from '../src/providers/AppThemeProvider';

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

const displayTimings: DisplayTiming[] = ['OFF', 'TAP_TO_SHOW', 'AFTER_ATTEMPT', 'ALWAYS'];
const themePreferences: ThemePreference[] = ['system', 'light', 'dark'];

export default function SettingsScreen() {
  const auth = useAuth();
  const { colors, preference: themePreference, setPreference: setThemePreference } = useAppTheme();
  const { locale, setLocale } = useAppLocale();
  const { t } = useTranslation(['mobile', 'common']);
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
        return mobileApi<Preferences>('/api/v1/learner/preferences', token).then(setPreferences);
      })
      .catch(() => setMessage(t('mobile:settings.loadFailed')))
      .finally(() => setLoading(false));
  }, [auth.authenticated, auth.getAccessToken, t]);

  if (!auth.loading && !auth.authenticated) return <Redirect href="/login" />;

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
      setMessage(t('mobile:settings.saved'));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : t('mobile:settings.saveFailed'));
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
          values={[...supportedLocales]}
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

      {loading ? (
        <ActivityIndicator color={colors.primary} />
      ) : (
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
          {message ? <Text className="text-xs leading-[19px] text-muted-foreground">{message}</Text> : null}
        </>
      )}

      <Button variant="outline" size="lg" onPress={() => void auth.signOut()}>
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
