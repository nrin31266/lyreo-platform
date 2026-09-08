import { useEffect } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useAuth } from '../src/auth';
import { useAppTheme } from '../src/providers/AppThemeProvider';

/**
 * System callback / navigation boundary for OAuth redirects matching `/auth`.
 *
 * This route does NOT implement OAuth logic (the auth/session provider owns
 * PKCE, state validation, and token exchange). It serves only to:
 * - Render a short loading state during the redirect transition
 * - Navigate to '/' once authenticated
 * - Navigate to '/login' on authentication failure or post-logout redirect
 * - Ensure users never remain on the callback route
 */
export default function AuthCallbackScreen() {
  const { authenticated, loading, error } = useAuth();
  const router = useRouter();
  const { colors } = useAppTheme();
  const params = useLocalSearchParams<{ code?: string; error?: string; error_description?: string }>();

  useEffect(() => {
    // 1. Once authenticated, replace to the home screen
    if (authenticated) {
      router.replace('/');
      return;
    }

    // 2. If provider returned an error or exchange failed, return to login
    if (params.error || error) {
      router.replace('/login');
      return;
    }

    // 3. Post-logout redirect or direct navigation without a code
    if (!params.code && !loading) {
      router.replace('/login');
      return;
    }
  }, [authenticated, loading, error, params.code, params.error, router]);

  // Safety fallback timeout: avoid being trapped on the callback route
  useEffect(() => {
    const timer = setTimeout(() => {
      if (!authenticated) {
        router.replace('/login');
      }
    }, 5000);
    return () => clearTimeout(timer);
  }, [authenticated, router]);

  return (
    <View className="flex-1 items-center justify-center bg-background">
      <ActivityIndicator size="large" color={colors.primary} />
    </View>
  );
}
