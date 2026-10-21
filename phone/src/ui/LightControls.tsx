import { useEffect, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, { interpolateColor, useAnimatedStyle, useSharedValue, withSpring } from 'react-native-reanimated';
import { LinearGradient } from 'expo-linear-gradient';
import type { LucideIcon } from 'lucide-react-native';
import { haptic } from '@/lib/haptics';
import { Press } from './Press';
import { Txt } from './Txt';
import { C, SPRING, withAlpha } from './theme';

const H = 48;
const PAD = 4;
const KNOB = H - PAD * 2;
const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

/**
 * A fat pill slider. Drag or tap anywhere on it. `onChange` fires on every step while the finger
 * moves, `onRelease` once with the final value. With `track` colours it paints the whole track
 * (warmth); without, it fills up to the knob in `accent` (brightness).
 */
export function LightSlider({
  label, icon: Icon, value, min, max, step = 1, format, onChange, onRelease, track, accent, dot, faded,
}: {
  label: string; icon: LucideIcon; value: number; min: number; max: number; step?: number;
  format: (v: number) => string; onChange: (v: number) => void; onRelease: (v: number) => void;
  track?: string[]; accent: string; dot?: (v: number) => string; faded?: boolean;
}) {
  const [w, setW] = useState(0);
  const [shown, setShown] = useState(value);
  const frac = (v: number) => (clamp(v, min, max) - min) / (max - min);
  const p = useSharedValue(frac(value));
  const dragging = useRef(false);
  const last = useRef(value);
  const travel = Math.max(1, w - PAD * 2 - KNOB);

  useEffect(() => {
    if (dragging.current) return;
    last.current = value;
    setShown(value);
    p.value = withSpring(frac(value), SPRING.settle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, min, max]);

  const at = (x: number) => {
    const f = clamp((x - PAD - KNOB / 2) / travel, 0, 1);
    return { f, v: clamp(Math.round((min + f * (max - min)) / step) * step, min, max) };
  };
  const move = (x: number, spring = false) => {
    const { f, v } = at(x);
    p.value = spring ? withSpring(f, SPRING.settle) : f;
    if (v === last.current) return;
    // a tick for every tenth of the range crossed, and at the ends
    const tenth = (n: number) => Math.floor(frac(n) * 10);
    if (tenth(v) !== tenth(last.current) || v === min || v === max) haptic.tick();
    last.current = v;
    setShown(v);
    onChange(v);
  };

  const pan = Gesture.Pan()
    .runOnJS(true)
    .activeOffsetX([-4, 4])
    .failOffsetY([-14, 14])
    .onBegin(() => { dragging.current = true; })
    .onStart((e) => move(e.x))
    .onUpdate((e) => move(e.x))
    .onEnd(() => onRelease(last.current))
    .onFinalize(() => { dragging.current = false; });
  const tap = Gesture.Tap().runOnJS(true).onEnd((e, ok) => {
    if (!ok) return;
    move(e.x, true);
    onRelease(last.current);
  });
  const g = Gesture.Exclusive(pan, tap);

  const knob = useAnimatedStyle(() => ({ transform: [{ translateX: p.value * travel }] }));
  const fill = useAnimatedStyle(() => ({ width: p.value * travel + KNOB + PAD * 2 }));

  return (
    <View>
      <View style={styles.head}>
        <Icon size={17} color={C.dim} strokeWidth={2} />
        <Txt v="label" color={C.dim} style={{ flex: 1 }}>{label}</Txt>
        <Txt v="strong">{format(shown)}</Txt>
      </View>
      <GestureDetector gesture={g}>
        <View onLayout={(e) => setW(e.nativeEvent.layout.width)} style={styles.track}
          accessible accessibilityRole="adjustable" accessibilityLabel={label} accessibilityValue={{ text: format(shown) }}
          accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
          onAccessibilityAction={(e) => {
            const big = Math.max(step, Math.round((max - min) / 10 / step) * step);
            const v = clamp(last.current + (e.nativeEvent.actionName === 'increment' ? big : -big), min, max);
            last.current = v;
            setShown(v);
            p.value = withSpring(frac(v), SPRING.settle);
            onRelease(v);
          }}>
          {track ? (
            <LinearGradient colors={track as [string, string, ...string[]]} start={{ x: 0, y: 0.5 }} end={{ x: 1, y: 0.5 }}
              style={[StyleSheet.absoluteFill, { opacity: faded ? 0.5 : 0.95 }]} />
          ) : (
            <Animated.View style={[styles.fill, fill]}>
              <LinearGradient colors={[withAlpha(accent, 0.28), withAlpha(accent, 0.95)]} start={{ x: 0, y: 0.5 }} end={{ x: 1, y: 0.5 }}
                style={StyleSheet.absoluteFill} />
            </Animated.View>
          )}
          {/* sheen along the top so the track reads as a lit tube */}
          <LinearGradient colors={['rgba(255,255,255,0.16)', 'rgba(255,255,255,0)']} style={styles.sheen} pointerEvents="none" />
          {w > 0 ? (
            <Animated.View style={[styles.knob, knob, faded && { opacity: 0.75 }]} pointerEvents="none">
              <View style={[styles.dot, { backgroundColor: dot ? dot(shown) : accent }]} />
            </Animated.View>
          ) : null}
        </View>
      </GestureDetector>
    </View>
  );
}

/** On/off switch whose lit track takes the light's colour. */
export function Toggle({ value, onChange, color, label }: { value: boolean; onChange: (v: boolean) => void; color: string; label: string }) {
  const t = useSharedValue(value ? 1 : 0);
  useEffect(() => { t.value = withSpring(value ? 1 : 0, SPRING.press); }, [value, t]);
  const track = useAnimatedStyle(() => ({ backgroundColor: interpolateColor(t.value, [0, 1], ['rgba(255,255,255,0.14)', color]) }));
  const knob = useAnimatedStyle(() => ({ transform: [{ translateX: t.value * 20 }] }));
  return (
    <Press onPress={() => onChange(!value)} feedback="tick" scaleTo={0.92} hitSlop={8}
      accessibilityRole="switch" accessibilityLabel={label} accessibilityState={{ checked: value }}>
      <Animated.View style={[styles.switch, track]}>
        <Animated.View style={[styles.switchKnob, knob]} />
      </Animated.View>
    </Press>
  );
}

/** Small icon in a soft round bubble, tinted by the light. */
export function IconBubble({ icon: Icon, color, size = 38 }: { icon: LucideIcon; color: string; size?: number }) {
  return (
    <View style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: withAlpha(color, 0.2), alignItems: 'center', justifyContent: 'center' }}>
      <Icon size={Math.round(size * 0.48)} color={C.text} strokeWidth={1.9} />
    </View>
  );
}

const styles = StyleSheet.create({
  head: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 10 },
  track: {
    height: H, borderRadius: H / 2, overflow: 'hidden', backgroundColor: C.fill,
    borderWidth: StyleSheet.hairlineWidth, borderColor: C.hair,
  },
  fill: { position: 'absolute', left: 0, top: 0, bottom: 0, borderRadius: H / 2, overflow: 'hidden' },
  sheen: { position: 'absolute', left: 0, right: 0, top: 0, height: H / 2 },
  knob: {
    position: 'absolute', left: PAD, top: PAD, width: KNOB, height: KNOB, borderRadius: KNOB / 2, backgroundColor: '#FFFFFF',
    alignItems: 'center', justifyContent: 'center',
    shadowColor: '#000', shadowOpacity: 0.3, shadowRadius: 6, shadowOffset: { width: 0, height: 2 }, elevation: 4,
  },
  dot: { width: 14, height: 14, borderRadius: 7, borderWidth: StyleSheet.hairlineWidth, borderColor: 'rgba(0,0,0,0.18)' },
  switch: { width: 52, height: 32, borderRadius: 16, padding: 3, justifyContent: 'center' },
  switchKnob: {
    width: 26, height: 26, borderRadius: 13, backgroundColor: '#FFFFFF',
    shadowColor: '#000', shadowOpacity: 0.28, shadowRadius: 4, shadowOffset: { width: 0, height: 1 }, elevation: 3,
  },
});
