import { Stack } from 'expo-router/stack';

export default function PublicLayout() {
  return <Stack initialRouteName="sign-in" screenOptions={{ headerShown: false }} />;
}
