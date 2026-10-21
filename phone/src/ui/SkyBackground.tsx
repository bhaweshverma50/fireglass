import { memo, useEffect } from 'react';
import { StyleSheet, useWindowDimensions, View } from 'react-native';
import Animated, {
  Easing, FadeIn, FadeOut, useAnimatedStyle, useReducedMotion, useSharedValue, withRepeat, withTiming,
} from 'react-native-reanimated';
import Svg, { Defs, RadialGradient, Rect, Stop } from 'react-native-svg';
import { LinearGradient } from 'expo-linear-gradient';
import { C } from './theme';

/** A soft round glow: an SVG radial gradient, colour at the centre fading to nothing. */
export const Glow = memo(function Glow({ color, size, opacity }: { color: string; size: number; opacity: number }) {
  const id = `g${color.replace('#', '')}${Math.round(opacity * 100)}`;
  return (
    <Svg width={size} height={size}>
      <Defs>
        <RadialGradient id={id} cx="50%" cy="50%" r="50%">
          <Stop offset="0" stopColor={color} stopOpacity={opacity} />
          <Stop offset="0.45" stopColor={color} stopOpacity={opacity * 0.55} />
          <Stop offset="1" stopColor={color} stopOpacity={0} />
        </RadialGradient>
      </Defs>
      <Rect width={size} height={size} fill={`url(#${id})`} />
    </Svg>
  );
});

// Per blob: colour, size (x screen width), home position (fractions), drift reach, speeds, phase.
const BLOBS = [
  { color: C.indigo, k: 1.5, x: 0.15, y: 0.12, reach: 0.18, sx: 1, sy: 0.7, ph: 0, a: 0.95 },
  { color: C.plum, k: 1.3, x: 0.85, y: 0.38, reach: 0.16, sx: 0.8, sy: 1.1, ph: 2, a: 0.75 },
  { color: C.teal, k: 1.4, x: 0.25, y: 0.82, reach: 0.2, sx: 1.2, sy: 0.9, ph: 4, a: 0.8 },
];

function Blob({ b, t, w, h }: { b: (typeof BLOBS)[number]; t: { value: number }; w: number; h: number }) {
  const size = w * b.k;
  const style = useAnimatedStyle(() => {
    const p = t.value * Math.PI * 2;
    return {
      transform: [
        { translateX: b.x * w - size / 2 + Math.sin(p * b.sx + b.ph) * b.reach * w },
        { translateY: b.y * h - size / 2 + Math.cos(p * b.sy + b.ph) * b.reach * h * 0.6 },
      ],
    };
  });
  return (
    <Animated.View style={[styles.abs, style]} pointerEvents="none">
      <Glow color={b.color} size={size} opacity={b.a} />
    </Animated.View>
  );
}

/**
 * The launcher's night sky: deep base, three slow drifting blobs of light, and the current app's
 * colour rising softly from the bottom. Still when reduced motion is on.
 */
export function SkyBackground({ accent }: { accent?: string | null }) {
  const { width: w, height: h } = useWindowDimensions();
  const reduced = useReducedMotion();
  const t = useSharedValue(0);
  useEffect(() => {
    if (reduced) return;
    t.value = withRepeat(withTiming(1, { duration: 48000, easing: Easing.linear }), -1, false);
  }, [reduced, t]);

  return (
    <View style={[StyleSheet.absoluteFill, { backgroundColor: C.base, overflow: 'hidden' }]} pointerEvents="none">
      {BLOBS.map((b, i) => <Blob key={i} b={b} t={t} w={w} h={h} />)}
      {accent ? (
        <Animated.View key={accent} entering={FadeIn.duration(900)} exiting={FadeOut.duration(900)}
          style={[styles.abs, { left: -w * 0.35, top: h - w * 0.75 }]}>
          <Glow color={accent} size={w * 1.7} opacity={0.32} />
        </Animated.View>
      ) : null}
      {/* depth: darker towards the top and bottom edges so glass reads clearly */}
      <LinearGradient
        colors={['rgba(7,11,31,0.55)', 'rgba(7,11,31,0)', 'rgba(7,11,31,0)', 'rgba(7,11,31,0.6)']}
        locations={[0, 0.25, 0.7, 1]}
        style={StyleSheet.absoluteFill}
      />
    </View>
  );
}

const styles = StyleSheet.create({ abs: { position: 'absolute', left: 0, top: 0 } });
