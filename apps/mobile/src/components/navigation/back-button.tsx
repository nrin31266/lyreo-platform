import { useRouter } from 'expo-router';
import { Pressable } from 'react-native';
import { Text } from '@/components/ui/text';

type BackButtonProps = {
  label: string;
  className?: string;
};

export function BackButton({ label, className }: BackButtonProps) {
  const router = useRouter();

  return (
    <Pressable
      accessibilityRole="button"
      className={className}
      onPress={() => {
        if (router.canGoBack()) router.back();
        else router.replace('/');
      }}
    >
      <Text className="font-bold text-primary">{label}</Text>
    </Pressable>
  );
}
