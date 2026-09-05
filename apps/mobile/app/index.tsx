import { Link, Redirect } from 'expo-router';
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useAuth } from '../src/auth';
import { colors, radius, shadow, spacing } from '../src/theme';

export default function HomeScreen() {
  const auth = useAuth();

  if (auth.loading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator />
      </View>
    );
  }

  if (!auth.authenticated) {
    return <Redirect href="/login" />;
  }

  return (
    <ScrollView contentContainerStyle={styles.page}>
      <View style={styles.top}>
        <View>
          <Text style={styles.eyebrow}>GOOD EVENING</Text>
          <Text style={styles.title}>Keep your English moving.</Text>
        </View>
        <View style={styles.diamond}>
          <Text>💎 240</Text>
        </View>
      </View>

      <View style={styles.level}>
        <View>
          <Text style={styles.muted}>LEVEL 12</Text>
          <Text style={styles.heading2}>Intermediate</Text>
        </View>
        <Text style={styles.gold}>82%</Text>
      </View>
      <View style={styles.progressTrack}>
        <View style={styles.progressFill} />
      </View>

      <Link href="/lesson" style={styles.hero}>
        <Text style={styles.heroSmall}>CONTINUE LEARNING · 12 MIN</Text>
        <Text style={styles.heroTitle}>Making plans naturally</Text>
        <Text style={styles.heroText}>Shadowing · Dictation · useful phrases</Text>
        <Text style={styles.heroAction}>Continue →</Text>
      </Link>

      <Text style={styles.section}>Today</Text>
      <View style={styles.grid}>
        <SummaryCard label="Vocabulary" value="18" note="words due" />
        <SummaryCard label="Study time" value="25m" note="2 activities" />
      </View>

      <Text style={styles.section}>Needs attention</Text>
      <View style={styles.card}>
        <Text style={styles.heading2}>Past perfect</Text>
        <Text style={styles.muted}>
          You missed this pattern in recent grammar practice.
        </Text>
        <Text style={styles.link}>Review a focused activity →</Text>
      </View>

      <View style={styles.nav}>
        <Link href="/progress" style={styles.link}>Progress</Link>
        <Link href="/settings" style={styles.link}>Learning settings</Link>
      </View>
    </ScrollView>
  );
}

function SummaryCard({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <View style={styles.card}>
      <Text style={styles.muted}>{label}</Text>
      <Text style={styles.big}>{value}</Text>
      <Text style={styles.muted}>{note}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  page: {
    padding: spacing.lg,
    paddingTop: 56,
    paddingBottom: 60,
    gap: 14,
  },
  top: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
  },
  eyebrow: {
    fontSize: 11,
    letterSpacing: 2,
    color: colors.leaf,
    fontWeight: '700',
  },
  title: {
    fontSize: 38,
    lineHeight: 42,
    fontWeight: '700',
    letterSpacing: -1.5,
    color: colors.ink,
    maxWidth: 290,
    marginTop: 6,
  },
  diamond: {
    backgroundColor: colors.goldSoft,
    padding: 11,
    borderRadius: radius.pill,
  },
  level: {
    marginTop: 18,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-end',
  },
  heading2: {
    fontSize: 20,
    fontWeight: '700',
    color: colors.ink,
  },
  muted: {
    color: colors.inkMuted,
    lineHeight: 20,
  },
  gold: {
    color: colors.gold,
    fontWeight: '800',
  },
  progressTrack: {
    height: 6,
    backgroundColor: colors.border,
    borderRadius: 99,
  },
  progressFill: {
    width: '82%',
    height: 6,
    backgroundColor: colors.forest,
    borderRadius: 99,
  },
  hero: {
    backgroundColor: colors.forest,
    borderRadius: radius.lg,
    padding: 24,
    marginTop: 12,
    ...shadow,
  },
  heroSmall: {
    fontSize: 10,
    letterSpacing: 1.4,
    color: '#B9D0C7',
    fontWeight: '700',
  },
  heroTitle: {
    color: colors.surface,
    fontWeight: '700',
    fontSize: 27,
    marginTop: 9,
  },
  heroText: {
    color: '#D6E3DE',
    marginTop: 8,
  },
  heroAction: {
    color: colors.goldSoft,
    fontWeight: '700',
    marginTop: 24,
  },
  section: {
    marginTop: 14,
    fontWeight: '800',
    fontSize: 15,
    color: colors.ink,
  },
  grid: {
    flexDirection: 'row',
    gap: 12,
  },
  card: {
    flex: 1,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.lg,
    padding: 18,
    ...shadow,
  },
  big: {
    fontSize: 30,
    fontWeight: '800',
    color: colors.forest,
    marginVertical: 4,
  },
  link: {
    color: colors.forest,
    fontWeight: '700',
    marginTop: 8,
  },
  nav: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingTop: 16,
  },
});
