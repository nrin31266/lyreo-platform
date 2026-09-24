import { Redirect } from 'expo-router';
import { View } from 'react-native';
import { useSession } from '@/auth/use-session';
import { LoadingState } from '@/components/states/loading-state';
import { HomeScreen } from '@/features/home/home-screen';
import { resolveRootRoute } from '@/navigation/root-route';

export default function IndexRoute() {
  const { status } = useSession();
  const route = resolveRootRoute(status);

  if (route.screen === 'loading') {
    return (
      <View className="flex-1 justify-center bg-background">
        <LoadingState />
      </View>
    );
  }

  if (route.screen === 'redirect') {
    return <Redirect href={route.href} />;
  }

  return <HomeScreen />;
}
