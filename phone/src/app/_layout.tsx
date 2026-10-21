import { useEffect } from 'react';
import { View } from 'react-native';
import { DarkTheme, Stack, ThemeProvider } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useFonts } from 'expo-font';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { useStore } from '@/state/store';
import { SkyBackground } from '@/ui/SkyBackground';
import { ToastHost } from '@/ui/Toast';
import { C, fontAssets } from '@/ui/theme';

SplashScreen.preventAutoHideAsync().catch(() => {});

// Navigators paint nothing so the shared sky shows through every screen.
const theme = { ...DarkTheme, colors: { ...DarkTheme.colors, background: 'transparent', card: 'transparent', primary: C.glow } };

function Sky() {
  // The sky takes on the colour of whatever is on the TV, like the launcher does.
  const accent = useStore((s) => (s.now ? s.apps.find((a) => a.pkg === s.now!.pkg)?.color ?? null : null));
  return <SkyBackground accent={accent} />;
}

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts(fontAssets);
  const hydrated = useStore((s) => s.hydrated);
  useEffect(() => { useStore.getState().hydrate(); }, []);
  const ready = (fontsLoaded || !!fontError) && hydrated;
  useEffect(() => { if (ready) SplashScreen.hideAsync().catch(() => {}); }, [ready]);

  return (
    <GestureHandlerRootView style={{ flex: 1, backgroundColor: C.base }}>
      <StatusBar style="light" />
      <Sky />
      {ready ? (
        <ThemeProvider value={theme}>
        <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: 'transparent' }, animation: 'fade' }}>
          <Stack.Screen name="index" />
          <Stack.Screen name="connect" />
          <Stack.Screen name="(tabs)" />
          <Stack.Screen name="models" options={{ animation: 'slide_from_right' }} />
          <Stack.Screen name="wallpapers" options={{ animation: 'slide_from_right' }} />
          <Stack.Screen name="lights" options={{ animation: 'slide_from_right' }} />
        </Stack>
        </ThemeProvider>
      ) : (
        <View style={{ flex: 1 }} />
      )}
      <ToastHost />
    </GestureHandlerRootView>
  );
}
