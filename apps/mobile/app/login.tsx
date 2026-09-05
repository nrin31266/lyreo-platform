import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { Redirect } from 'expo-router';
import { useAuth } from '../src/auth';
import { colors, radius, spacing } from '../src/theme';

export default function Login() {
  const auth = useAuth();
  if (auth.loading) return <View style={s.center}><ActivityIndicator /></View>;
  if (auth.authenticated) return <Redirect href="/" />;
  return <View style={s.page}>
    <View style={s.mark}><Text style={s.wave}>〰</Text></View>
    <Text style={s.name}>Lyreo</Text>
    <Text style={s.tagline}>Listen. Notice. Speak.</Text>
    <Text style={s.copy}>Your learning path, vocabulary reviews, TOEIC history and speaking progress stay with your account.</Text>
    <Pressable style={s.button} onPress={auth.signIn}><Text style={s.buttonText}>Continue with Lyreo account</Text></Pressable>
    <Text style={s.note}>Secure sign-in uses Keycloak Authorization Code + PKCE. Lyreo never asks your password through its API.</Text>
  </View>;
}

const s = StyleSheet.create({
  page: { flex: 1, padding: spacing.xl, justifyContent: 'center', backgroundColor: '#F7F8F5' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  mark: { width: 64, height: 64, borderRadius: 22, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.forest },
  wave: { color: 'white', fontSize: 34, fontWeight: '700' },
  name: { fontSize: 44, fontWeight: '800', color: colors.ink, marginTop: 22, letterSpacing: -1.5 },
  tagline: { color: colors.leaf, fontSize: 17, fontWeight: '700', marginTop: 4 },
  copy: { color: colors.inkMuted, fontSize: 16, lineHeight: 24, marginTop: 24, maxWidth: 380 },
  button: { marginTop: 34, backgroundColor: colors.forest, paddingVertical: 17, paddingHorizontal: 20, borderRadius: radius.lg, alignItems: 'center' },
  buttonText: { color: 'white', fontWeight: '800', fontSize: 16 },
  note: { color: colors.inkMuted, fontSize: 12, lineHeight: 18, marginTop: 18 },
});
