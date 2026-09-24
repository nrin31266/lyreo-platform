import '../global.css';
import { View } from 'react-native';
import { Stack } from 'expo-router/stack';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { useSession } from '@/auth/use-session';
import { LoadingState } from '@/components/states/loading-state';
import { AppProviders } from '@/providers/AppProviders';
import { useAppTheme } from '@/providers/AppThemeProvider';

void SplashScreen.preventAutoHideAsync();

function ThemedNavigator() {
  const { colors, mode } = useAppTheme();
  const { status } = useSession();

  useEffect(() => {
    if (status !== 'bootstrapping') void SplashScreen.hideAsync();
  }, [status]);

  if (status === 'bootstrapping') {
    return (
      <View className="flex-1 justify-center bg-background">
        <StatusBar style={mode === 'dark' ? 'light' : 'dark'} />
        <LoadingState />
      </View>
    );
  }

  return (
    <>
      <StatusBar style={mode === 'dark' ? 'light' : 'dark'} />
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: colors.background },
        }}
      >
        <Stack.Protected guard={status === 'unauthenticated'}>
          <Stack.Screen name="(public)" />
        </Stack.Protected>
        <Stack.Protected guard={status === 'authenticated'}>
          <Stack.Screen name="(app)" />
        </Stack.Protected>
        <Stack.Screen name="auth/callback" />
        <Stack.Screen name="+not-found" />
      </Stack>
    </>
  );
}

export default function RootLayout() {
  return (
    <AppProviders>
      <ThemedNavigator />
    </AppProviders>
  );
}
