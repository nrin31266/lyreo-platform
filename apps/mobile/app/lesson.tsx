import { Link } from 'expo-router';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { colors, radius, spacing } from '../src/theme';

/**
 * Representative Shadowing screen, not the final player implementation.
 *
 * It demonstrates one important Lyreo UX rule: contextual vocabulary/grammar/IPA are
 * sentence support annotations. They do not silently become dedicated Vocabulary or
 * Grammar Practice activities.
 */
export default function LessonScreen() {
  const [showIpa, setShowIpa] = useState(false);
  const [recorded, setRecorded] = useState(false);

  return (
    <ScrollView contentContainerStyle={styles.page}>
      <Link href="/" style={styles.back}>← Home</Link>
      <Text style={styles.eyebrow}>SHADOWING · SENTENCE 3/12</Text>

      <View style={styles.waveCard}>
        <Text style={styles.wave}>▂▄▆█▅▃▂▅▇▆▃▂</Text>
        <Text style={styles.small}>0.85× · repeat sentence</Text>
      </View>

      <Text style={styles.sentence}>
        Would you like to grab a cup of coffee after work?
      </Text>
      <Text style={styles.chunk}>
        Would you LIKE / to GRAB a CUP of COFFEE / after WORK?
      </Text>

      {showIpa ? (
        <View style={styles.noteCard}>
          <Text style={styles.noteTitle}>Sentence pronunciation</Text>
          <Text style={styles.small}>
            IPA is an optional, accent-aware annotation. In production it is loaded from
            cached/on-demand lesson data according to admin policy and learner preference.
          </Text>
        </View>
      ) : null}

      <Pressable style={styles.secondary} onPress={() => setShowIpa(value => !value)}>
        <Text style={styles.secondaryText}>{showIpa ? 'Hide' : 'Show'} IPA</Text>
      </Pressable>

      <Pressable style={styles.record} onPress={() => setRecorded(true)}>
        <Text style={styles.recordText}>● {recorded ? 'Recorded' : 'Hold to shadow'}</Text>
      </Pressable>

      {recorded ? (
        <View style={styles.feedback}>
          <View style={styles.scoreBlock}>
            <Text style={styles.scoreValue}>84</Text>
            <Text style={styles.small}>Timing & word accuracy</Text>
          </View>

          <Text style={styles.noteTitle}>Useful expressions</Text>
          <Text style={styles.item}>grab a cup of coffee · đi uống một tách cà phê</Text>
          <Text style={styles.item}>would you like to… · bạn có muốn…</Text>

          <Text style={styles.noteTitle}>Grammar note</Text>
          <Text style={styles.item}>would you like to + V · lời mời/lời đề nghị lịch sự</Text>

          <Text style={styles.small}>
            These notes are contextual support. They are not Vocabulary Practice or Grammar
            Practice unless this Lesson explicitly includes those activities.
          </Text>
        </View>
      ) : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  page: {
    padding: spacing.lg,
    paddingTop: 54,
    paddingBottom: 60,
  },
  back: {
    color: colors.forest,
    fontWeight: '700',
    marginBottom: 36,
  },
  eyebrow: {
    letterSpacing: 1.6,
    color: colors.leaf,
    fontSize: 11,
    fontWeight: '800',
  },
  waveCard: {
    backgroundColor: colors.forest,
    borderRadius: radius.lg,
    padding: 22,
    alignItems: 'center',
    marginVertical: 18,
  },
  wave: {
    color: colors.goldSoft,
    fontSize: 26,
    letterSpacing: 3,
  },
  small: {
    color: colors.inkMuted,
    lineHeight: 20,
    fontSize: 13,
  },
  sentence: {
    fontSize: 34,
    lineHeight: 44,
    fontWeight: '700',
    letterSpacing: -1.2,
    color: colors.ink,
    marginTop: 18,
  },
  chunk: {
    fontSize: 15,
    color: colors.leaf,
    lineHeight: 23,
    marginTop: 14,
    fontWeight: '700',
  },
  secondary: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    padding: 13,
    alignItems: 'center',
    marginTop: 22,
  },
  secondaryText: {
    color: colors.forest,
    fontWeight: '700',
  },
  record: {
    backgroundColor: colors.forest,
    borderRadius: radius.pill,
    padding: 18,
    alignItems: 'center',
    marginTop: 14,
  },
  recordText: {
    color: colors.surface,
    fontWeight: '800',
  },
  noteCard: {
    backgroundColor: colors.surface,
    padding: 18,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    marginTop: 18,
  },
  noteTitle: {
    fontWeight: '800',
    color: colors.ink,
    marginTop: 14,
    marginBottom: 6,
  },
  feedback: {
    marginTop: 20,
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: 20,
    borderWidth: 1,
    borderColor: colors.border,
  },
  scoreBlock: {
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
    paddingBottom: 16,
  },
  scoreValue: {
    fontSize: 45,
    fontWeight: '800',
    color: colors.forest,
  },
  item: {
    color: colors.ink,
    lineHeight: 23,
  },
});
