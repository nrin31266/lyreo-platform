import { Link } from 'expo-router';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { colors, radius, spacing } from '../src/theme';

const skills = [
  ['Listening', 72],
  ['Speaking', 64],
  ['Vocabulary', 81],
  ['Grammar', 69],
] as const;

export default function ProgressScreen() {
  return (
    <ScrollView contentContainerStyle={styles.page}>
      <Link href="/" style={styles.back}>← Home</Link>
      <Text style={styles.title}>Your progress</Text>
      <Text style={styles.lead}>
        Analytics is a projection. Lesson, TOEIC, Vocabulary and Curriculum remain the owners
        of their detailed progress and attempt history.
      </Text>

      {skills.map(([name, value]) => (
        <View style={styles.skillCard} key={name}>
          <View style={styles.skillLabel}>
            <Text style={styles.name}>{name}</Text>
            <Text style={styles.value}>{value}</Text>
          </View>
          <View style={styles.bar}>
            <View style={[styles.fill, { width: `${value}%` }]} />
          </View>
        </View>
      ))}

      <View style={styles.weekCard}>
        <Text style={styles.name}>This week</Text>
        <Text style={styles.big}>2h 45m · 5 days</Text>
        <Text style={styles.lead}>12 vocabulary reviews · 3 lessons · 1 TOEIC drill</Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  page: {
    padding: spacing.lg,
    paddingTop: 54,
    gap: 16,
  },
  back: {
    color: colors.forest,
    fontWeight: '700',
  },
  title: {
    fontSize: 40,
    fontWeight: '800',
    letterSpacing: -1.5,
    color: colors.ink,
    marginTop: 18,
  },
  lead: {
    color: colors.inkMuted,
    lineHeight: 22,
  },
  skillCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: 18,
    borderWidth: 1,
    borderColor: colors.border,
  },
  skillLabel: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  name: {
    fontWeight: '800',
    color: colors.ink,
  },
  value: {
    fontWeight: '800',
    color: colors.leaf,
  },
  bar: {
    height: 6,
    backgroundColor: colors.border,
    borderRadius: 9,
    marginTop: 12,
  },
  fill: {
    height: 6,
    backgroundColor: colors.forest,
    borderRadius: 9,
  },
  weekCard: {
    backgroundColor: colors.goldSoft,
    borderRadius: radius.lg,
    padding: 20,
    marginTop: 10,
  },
  big: {
    fontSize: 24,
    fontWeight: '800',
    color: colors.forest,
    marginVertical: 6,
  },
});
