import '../global.css';
import * as WebBrowser from 'expo-web-browser';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { AppProviders } from '../src/providers/AppProviders';
import { useAppTheme } from '../src/providers/AppThemeProvider';

WebBrowser.maybeCompleteAuthSession();

function ThemedNavigator() {
  const { colors, mode } = useAppTheme();
  return (
    <>
      <StatusBar style={mode === 'dark' ? 'light' : 'dark'} />
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: colors.background },
        }}
      />
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
