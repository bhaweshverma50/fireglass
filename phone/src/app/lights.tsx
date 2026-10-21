import { useCallback, useEffect, useRef, useState } from 'react';
import { ScrollView, StyleSheet, useWindowDimensions, View } from 'react-native';
import { router, useFocusEffect } from 'expo-router';
import { LinearGradient } from 'expo-linear-gradient';
import Animated, {
  Easing, FadeIn, FadeInDown, FadeOut, LinearTransition, useAnimatedStyle, useReducedMotion, useSharedValue, withDelay,
  withRepeat, withSpring, withTiming,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Circle, Defs, RadialGradient, Stop } from 'react-native-svg';
import {
  ArrowLeft, Check, ChevronDown, Clapperboard, House, Lightbulb, LightbulbOff, Palette, Power, ScanSearch, Sparkles, Sun,
  Thermometer, WifiOff,
} from 'lucide-react-native';
import { ApiError } from '@/lib/api';
import { haptic } from '@/lib/haptics';
import {
  applyPatch, bulbHex, describe, DIM_MAX, DIM_MIN, fromHex, leadBulb, mergePatch, orderScenes, sceneColors, swatchFor,
  SWATCHES, TEMP_MAX, TEMP_MIN, TEMP_STOPS, tempToRgb, toHex,
} from '@/lib/lights';
import type { Bulb, LightScene, Lights, LightsAuto, LightsPatch } from '@/lib/types';
import { activeClient } from '@/state/store';
import { Button, RoundButton } from '@/ui/Controls';
import { Glass } from '@/ui/Glass';
import { IconBubble, LightSlider, Toggle } from '@/ui/LightControls';
import { Press } from '@/ui/Press';
import { Shimmer } from '@/ui/Shimmer';
import { Glow } from '@/ui/SkyBackground';
import { toast } from '@/ui/Toast';
import { Txt } from '@/ui/Txt';
import { C, R, S, SPRING, withAlpha } from '@/ui/theme';

/** Least time between two requests to the TV while a slider moves. */
const GAP = 120;
/** How often to look for changes made elsewhere (the TV's own automations, the WiZ app). */
const POLL = 8000;
const OFF_GLOW = '#5B66C9';

type Problem = 'offline' | 'unsupported' | 'other';
const problemOf = (e: unknown): Problem =>
  e instanceof ApiError && (e.code === 'offline' || e.code === 'timeout') ? 'offline'
    : e instanceof ApiError && e.status === 404 ? 'unsupported' : 'other';

/**
 * State of the lights with optimistic changes. Changes go out one request at a time, at most one
 * per GAP, merged so only the latest value of each field is sent; the TV's answer replaces the
 * guess only once nothing newer is waiting and no finger is on a slider.
 */
function useLights() {
  const [data, setData] = useState<Lights | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const q = useRef({ pending: null as LightsPatch | null, busy: false, last: 0, timer: null as ReturnType<typeof setTimeout> | null, dragging: false, touched: 0, toasted: 0 });
  const quiet = () => {
    const s = q.current;
    return !s.pending && !s.busy && !s.dragging && Date.now() - s.touched > 1500;
  };

  const load = useCallback(async (force = false) => {
    const c = activeClient();
    if (!c) return;
    try {
      const l = await c.lights();
      if (force || quiet()) setData(l);
      setProblem(null);
    } catch (e) {
      setProblem(problemOf(e));
    }
  }, []);

  const pump = useCallback(() => {
    const s = q.current;
    if (s.busy || !s.pending || s.timer) return;
    const wait = s.last + GAP - Date.now();
    if (wait > 0) {
      s.timer = setTimeout(() => { s.timer = null; pump(); }, wait);
      return;
    }
    const c = activeClient();
    if (!c) return;
    const p = s.pending;
    s.pending = null;
    s.busy = true;
    s.last = Date.now();
    setData((d) => (d ? applyPatch(d, p) : d));
    c.setLights(p).then(
      (l) => {
        s.busy = false;
        if (!s.pending && !s.dragging) setData(l);
        pump();
      },
      (e) => {
        s.busy = false;
        if (Date.now() - s.toasted > 3000) {
          s.toasted = Date.now();
          const code = e instanceof ApiError ? e.code : '';
          toast(code === 'no_bulbs' ? 'The TV has lost the light' : code === 'offline' || code === 'timeout' ? "Couldn't reach the TV" : "The light didn't answer", 'error');
          haptic.err();
        }
        if (!s.pending) load(true);
        pump();
      },
    );
  }, [load]);

  /** `live` changes (a slider moving) are shown when they go out, not on every step. */
  const send = useCallback((p: LightsPatch, live = false) => {
    const s = q.current;
    s.touched = Date.now();
    s.pending = s.pending ? mergePatch(s.pending, p) : p;
    if (!live) setData((d) => (d ? applyPatch(d, p) : d));
    pump();
  }, [pump]);

  const setDragging = useCallback((on: boolean) => { q.current.dragging = on; }, []);

  const setAuto = useCallback(async (p: Partial<LightsAuto>) => {
    const c = activeClient();
    if (!c) return;
    setData((d) => (d ? { ...d, auto: { ...d.auto, ...p } } : d));
    try {
      const l = await c.setLightsAuto(p);
      setData((d) => (d && !quiet() ? { ...d, auto: l.auto } : l));
    } catch {
      toast("Couldn't reach the TV", 'error');
      load(true);
    }
  }, [load]);

  useEffect(() => () => { if (q.current.timer) clearTimeout(q.current.timer); }, []);

  return { data, setData, problem, setProblem, load, send, setDragging, setAuto };
}

/** Follows `v`, but changes at most once per `ms`, so glows crossfade instead of thrashing. */
function useThrottled<T>(v: T, ms: number): T {
  const [out, setOut] = useState(v);
  const last = useRef(0);
  useEffect(() => {
    const wait = last.current + ms - Date.now();
    if (wait <= 0) { last.current = Date.now(); setOut(v); return; }
    const t = setTimeout(() => { last.current = Date.now(); setOut(v); }, wait);
    return () => clearTimeout(t);
  }, [v, ms]);
  return out;
}

export default function LightsScreen() {
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const { data, setData, problem, setProblem, load, send, setDragging, setAuto } = useLights();
  const [discovering, setDiscovering] = useState(false);
  const [searched, setSearched] = useState(false);

  useFocusEffect(useCallback(() => {
    load(true);
    const t = setInterval(() => load(), POLL);
    return () => clearInterval(t);
  }, [load]));

  const discover = async () => {
    const c = activeClient();
    if (!c || discovering) return;
    setDiscovering(true);
    try {
      const l = await c.discoverLights();
      setData(l);
      setProblem(null);
      setSearched(true);
      if (l.bulbs.some((b) => b.online)) { haptic.ok(); toast(l.bulbs.length === 1 ? 'Found your light' : `Found ${l.bulbs.length} lights`); }
    } catch (e) {
      const p = problemOf(e);
      if (p === 'offline') setProblem(p);
      else toast("Couldn't look for lights just now", 'error');
    } finally {
      setDiscovering(false);
    }
  };

  const bulb = leadBulb(data);
  const scenes = data?.scenes ?? [];
  const live = !!bulb?.online;
  const hex = bulbHex(bulb, scenes);
  const accent = hex ?? C.glow;
  const level = live && bulb?.on ? (bulb.dimming ?? 100) / 100 : 0;
  const glow = useThrottled(hex ?? OFF_GLOW, 180);
  const glowLevel = hex ? 0.22 + level * 0.3 : 0.14;
  const online = data?.bulbs.filter((b) => b.online).length ?? 0;

  return (
    <View style={{ flex: 1, paddingTop: insets.top + 6 }}>
      {/* the room, lit by the light: a wash of its colour from the top and a little from below */}
      <View style={StyleSheet.absoluteFill} pointerEvents="none">
        <Animated.View key={`a${glow}`} entering={FadeIn.duration(700)} exiting={FadeOut.duration(700)}
          style={[styles.abs, { left: -width * 0.35, top: insets.top + 230 - width * 0.85 }]}>
          <Glow color={glow} size={width * 1.7} opacity={glowLevel} />
        </Animated.View>
        <Animated.View key={`b${glow}`} entering={FadeIn.duration(900)} exiting={FadeOut.duration(900)}
          style={[styles.abs, { right: -width * 0.6, bottom: -width * 0.55 }]}>
          <Glow color={glow} size={width * 1.4} opacity={glowLevel * 0.55} />
        </Animated.View>
      </View>

      <View style={styles.top}>
        <RoundButton icon={ArrowLeft} size={44} label="Back" feedback="tap" onPress={() => router.back()} />
        {data?.bulbs.length ? (
          <RoundButton icon={ScanSearch} size={44} label="Look for lights again" feedback="tap" disabled={discovering} onPress={discover} />
        ) : null}
      </View>

      <ScrollView contentContainerStyle={{ padding: S.gutter, paddingTop: 8, paddingBottom: insets.bottom + 48 }}>
        <Animated.View entering={FadeInDown.duration(450)}>
          <Txt v="display">Lights</Txt>
          <Txt v="body" color={C.dim} style={{ marginTop: 8 }}>
            {data && data.bulbs.length > 1
              ? `${data.bulbs.length} WiZ lights near your TV, set together.`
              : 'The WiZ light near your TV.'}
          </Txt>
        </Animated.View>

        {problem && data ? (
          <Animated.View entering={FadeIn} style={styles.lost}>
            <WifiOff size={15} color={C.warn} />
            <Txt v="caption" color={C.warn} style={{ flex: 1 }}>Lost the TV. This is how the light was.</Txt>
            <Press onPress={() => load(true)} accessibilityRole="button"><Txt v="label" color={C.glow}>Try again</Txt></Press>
          </Animated.View>
        ) : null}

        {!data && problem ? (
          <ProblemCard problem={problem} onRetry={() => load(true)} />
        ) : !data ? (
          <Loading width={width} />
        ) : !data.bulbs.length ? (
          <EmptyState discovering={discovering} searched={searched} onDiscover={discover} />
        ) : !live || !bulb ? (
          <SilentCard discovering={discovering} onDiscover={discover} />
        ) : (
          <>
            <Hero bulb={bulb} hex={hex} level={level} scenes={scenes}
              note={online < data.bulbs.length ? `${data.bulbs.length - online} of ${data.bulbs.length} lights aren't answering` : null}
              onToggle={() => send({ on: !bulb.on })} />

            <Animated.View entering={FadeInDown.delay(80).duration(450)}>
              <Glass radius={R.lg} style={styles.card}>
                <LightSlider label="Brightness" icon={Sun} value={bulb.dimming ?? DIM_MAX} min={DIM_MIN} max={DIM_MAX}
                  format={(v) => `${v}%`} accent={accent}
                  onChange={(v) => { setDragging(true); send({ dimming: v }, true); }}
                  onRelease={(v) => { setDragging(false); send({ dimming: v }); }} />
                <View style={{ height: 22 }} />
                <LightSlider label="Warmth" icon={Thermometer} value={bulb.temp ?? 2700} min={TEMP_MIN} max={TEMP_MAX} step={50}
                  format={(v) => (bulb.temp != null ? `${v} K` : 'Not in use')} track={TEMP_STOPS} accent={accent} faded={bulb.temp == null}
                  dot={(v) => toHex(tempToRgb(v))}
                  onChange={(v) => { setDragging(true); send({ temp: v }, true); }}
                  onRelease={(v) => { setDragging(false); send({ temp: v }); }} />
              </Glass>
            </Animated.View>

            <Animated.View entering={FadeInDown.delay(140).duration(450)}>
              <Swatches width={width} bulb={bulb} onPick={(rgb) => send({ rgb })} />
            </Animated.View>

            <Animated.View entering={FadeInDown.delay(200).duration(450)}>
              <Scenes width={width} scenes={scenes} active={bulb.on ? bulb.scene : null} onPick={(id) => send({ scene: id })} />
            </Animated.View>
          </>
        )}

        {data && data.bulbs.length ? (
          <Animated.View entering={FadeInDown.delay(260).duration(450)}>
            <Txt v="serif" style={styles.section}>Automatic</Txt>
            <Glass radius={R.lg} style={{ paddingHorizontal: 16 }}>
              <AutoRow icon={Clapperboard} color={accent} title="Dim while something plays"
                sub="Lowers the light when a show starts, and brings it back after."
                value={data.auto.dimWhilePlaying} onChange={(v) => setAuto({ dimWhilePlaying: v })} />
              <View style={styles.rule} />
              <AutoRow icon={House} color={accent} title="Match the home screen"
                sub="Tints the light to the colours of the TV's home."
                value={data.auto.matchHome} onChange={(v) => setAuto({ matchHome: v })} />
            </Glass>
          </Animated.View>
        ) : null}
      </ScrollView>
    </View>
  );
}

/** The light itself: a big disc in its colour that turns it on and off, breathing softly while lit. */
function Hero({ bulb, hex, level, scenes, note, onToggle }: {
  bulb: Bulb; hex: string | null; level: number; scenes: LightScene[]; note: string | null; onToggle: () => void;
}) {
  const reduced = useReducedMotion();
  const on = !!bulb.on && !!hex;
  const lit = useSharedValue(on ? 1 : 0);
  const breath = useSharedValue(0);
  const color = useSharedValue(hex ?? OFF_GLOW);
  const halo = useThrottled(hex ?? OFF_GLOW, 180);

  useEffect(() => { lit.value = withTiming(on ? 1 : 0, { duration: 520, easing: Easing.out(Easing.cubic) }); }, [on, lit]);
  useEffect(() => { if (hex) color.value = withTiming(hex, { duration: 420 }); }, [hex, color]);
  useEffect(() => {
    if (!on || reduced) { breath.value = withTiming(0, { duration: 400 }); return; }
    breath.value = withRepeat(withTiming(1, { duration: 3400, easing: Easing.inOut(Easing.sin) }), -1, true);
  }, [on, reduced, breath]);

  const haloStyle = useAnimatedStyle(() => ({
    opacity: lit.value * (0.45 + 0.55 * Math.max(level, 0.15)) * (0.82 + breath.value * 0.18),
    transform: [{ scale: 0.86 + lit.value * 0.08 + breath.value * 0.06 }],
  }));
  const discStyle = useAnimatedStyle(() => ({ opacity: lit.value, backgroundColor: color.value }));

  const [r, g, b] = fromHex(hex ?? '#000000');
  const lightDisc = (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.55;
  const D = 148;
  const sub = on
    ? [`${bulb.dimming ?? 100}%`, bulb.temp ? `${bulb.temp} K` : null].filter(Boolean).join('  ·  ')
    : 'Tap to turn on';

  return (
    <Animated.View entering={FadeInDown.delay(40).duration(500)} style={{ alignItems: 'center', marginTop: 18, marginBottom: 22 }}>
      <View style={{ width: 300, height: 260, alignItems: 'center', justifyContent: 'center' }}>
        <Animated.View style={[StyleSheet.absoluteFill, { alignItems: 'center', justifyContent: 'center' }, haloStyle]} pointerEvents="none">
          <Animated.View key={halo} entering={FadeIn.duration(500)} exiting={FadeOut.duration(500)} style={styles.abs}>
            <Glow color={halo} size={300} opacity={0.9} />
          </Animated.View>
        </Animated.View>
        <Press onPress={onToggle} scaleTo={0.92} feedback="press" hitSlop={10}
          accessibilityRole="switch" accessibilityLabel="Light" accessibilityState={{ checked: on }}>
          <View style={{ width: D, height: D }}>
            <Glass radius={D / 2} style={[StyleSheet.absoluteFill, { borderRadius: D / 2 }]} />
            <Animated.View style={[StyleSheet.absoluteFill, { borderRadius: D / 2, overflow: 'hidden' }, discStyle]}>
              {/* a highlight up and to the left, and a little shade round the edge, so it reads as a glowing bulb */}
              <Svg width={D} height={D} style={StyleSheet.absoluteFill}>
                <Defs>
                  <RadialGradient id="disc-sheen" cx="38%" cy="32%" r="62%">
                    <Stop offset="0" stopColor="#FFFFFF" stopOpacity={0.62} />
                    <Stop offset="0.55" stopColor="#FFFFFF" stopOpacity={0.08} />
                    <Stop offset="1" stopColor="#000000" stopOpacity={0.16} />
                  </RadialGradient>
                </Defs>
                <Circle cx={D / 2} cy={D / 2} r={D / 2} fill="url(#disc-sheen)" />
              </Svg>
            </Animated.View>
            <View style={[StyleSheet.absoluteFill, { alignItems: 'center', justifyContent: 'center' }]} pointerEvents="none">
              <Power size={46} strokeWidth={2.1} color={on ? (lightDisc ? '#1A1426' : '#FFFFFF') : C.dim} />
            </View>
          </View>
        </Press>
      </View>
      <Txt v="title" style={{ textAlign: 'center', marginTop: -6 }}>{describe(bulb, scenes)}</Txt>
      <Txt v="caption" color={C.faint} style={{ textAlign: 'center', marginTop: 4 }}>{sub}</Txt>
      {note ? <Txt v="caption" color={C.warn} style={{ textAlign: 'center', marginTop: 6 }}>{note}</Txt> : null}
    </Animated.View>
  );
}

function Swatches({ width, bulb, onPick }: { width: number; bulb: Bulb; onPick: (rgb: [number, number, number]) => void }) {
  const inner = width - S.gutter * 2 - 32;
  const cell = inner / 5;
  const dot = Math.min(44, cell - 16);
  const picked = bulb.on ? swatchFor(bulb.rgb) : null;
  return (
    <Glass radius={R.lg} style={[styles.card, { marginTop: S.gap }]}>
      <View style={styles.cardHead}>
        <Palette size={17} color={C.dim} strokeWidth={2} />
        <Txt v="label" color={C.dim}>Colour</Txt>
      </View>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', rowGap: 10 }}>
        {SWATCHES.map((s) => (
          <SwatchDot key={s.name} name={s.name} hex={toHex(s.rgb)} size={dot} cell={cell} on={picked?.name === s.name}
            onPress={() => onPick(s.rgb)} />
        ))}
      </View>
    </Glass>
  );
}

function SwatchDot({ name, hex, size, cell, on, onPress }: { name: string; hex: string; size: number; cell: number; on: boolean; onPress: () => void }) {
  const t = useSharedValue(on ? 1 : 0);
  useEffect(() => { t.value = withSpring(on ? 1 : 0, SPRING.settle); }, [on, t]);
  const lift = useAnimatedStyle(() => ({ transform: [{ scale: 1 + t.value * 0.1 }, { translateY: -t.value * 2 }] }));
  const ring = useAnimatedStyle(() => ({ opacity: t.value, transform: [{ scale: 0.8 + t.value * 0.2 }] }));
  const [r, g, b] = fromHex(hex);
  const light = (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.6;
  const ringSize = size + 14;
  return (
    <Press onPress={onPress} scaleTo={0.88} feedback="tick" style={{ width: cell, alignItems: 'center' }}
      accessibilityRole="button" accessibilityLabel={name} accessibilityState={{ selected: on }}>
      <View style={{ width: ringSize, height: ringSize, alignItems: 'center', justifyContent: 'center' }}>
        <Animated.View style={[styles.abs, { width: ringSize, height: ringSize, borderRadius: ringSize / 2, backgroundColor: withAlpha(hex, 0.32) }, ring]} />
        <Animated.View style={[{ width: size, height: size, borderRadius: size / 2, overflow: 'hidden' }, lift]}>
          <LinearGradient colors={[mix(hex, '#FFFFFF', 0.35), hex]} start={{ x: 0.2, y: 0 }} end={{ x: 0.8, y: 1 }}
            style={[StyleSheet.absoluteFill, { alignItems: 'center', justifyContent: 'center' }]}>
            {on ? <Check size={18} strokeWidth={2.6} color={light ? '#1A1426' : '#FFFFFF'} /> : null}
          </LinearGradient>
        </Animated.View>
      </View>
    </Press>
  );
}

function Scenes({ width, scenes, active, onPick }: { width: number; scenes: LightScene[]; active: number | null; onPick: (id: number) => void }) {
  const [all, setAll] = useState(false);
  const { top, rest } = orderScenes(scenes);
  const shown = all || !top.length ? [...top, ...rest] : top;
  // Keep the scene that's running in view even when it's one of the others.
  const extra = !all && active != null && !shown.some((s) => s.id === active) ? scenes.filter((s) => s.id === active) : [];
  const tile = (width - S.gutter * 2 - S.gap) / 2;
  if (!scenes.length) return null;
  return (
    <View>
      <View style={[styles.cardHead, { marginTop: 30, marginBottom: 12 }]}>
        <Sparkles size={18} color={C.dim} strokeWidth={2} />
        <Txt v="serif" style={{ flex: 1 }}>Scenes</Txt>
      </View>
      <Animated.View layout={LinearTransition.springify().damping(20)} style={{ flexDirection: 'row', flexWrap: 'wrap', gap: S.gap }}>
        {[...shown, ...extra].map((s, i) => (
          <Animated.View key={s.id} entering={FadeIn.delay(Math.min(i, 12) * 25)} layout={LinearTransition}>
            <SceneTile scene={s} width={tile} on={s.id === active} onPress={() => onPick(s.id)} />
          </Animated.View>
        ))}
      </Animated.View>
      {rest.length && top.length ? (
        <Press onPress={() => setAll(!all)} feedback="tick" style={styles.more} accessibilityRole="button">
          <Txt v="label" color={C.glow}>{all ? 'Fewer scenes' : `All ${scenes.length} scenes`}</Txt>
          <ChevronDown size={16} color={C.glow} style={{ transform: [{ rotate: all ? '180deg' : '0deg' }] }} />
        </Press>
      ) : null}
    </View>
  );
}

function SceneTile({ scene, width, on, onPress }: { scene: LightScene; width: number; on: boolean; onPress: () => void }) {
  const [a, b] = sceneColors(scene.name);
  return (
    <Press onPress={onPress} scaleTo={0.95} feedback="tick" accessibilityRole="button" accessibilityLabel={scene.name}
      accessibilityState={{ selected: on }}>
      <Glass radius={R.md} tint={on ? withAlpha(a, 0.24) : undefined} style={[styles.scene, { width }]}>
        <View style={styles.orbWrap}>
          {on ? <View style={[styles.orbHalo, { backgroundColor: withAlpha(a, 0.4) }]} /> : null}
          <LinearGradient colors={[a, b]} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={styles.orb} />
        </View>
        <Txt v="label" numberOfLines={1} style={{ flex: 1 }} color={on ? C.text : C.dim}>{scene.name}</Txt>
      </Glass>
    </Press>
  );
}

function AutoRow({ icon, color, title, sub, value, onChange }: {
  icon: typeof Clapperboard; color: string; title: string; sub: string; value: boolean; onChange: (v: boolean) => void;
}) {
  return (
    <View style={styles.autoRow}>
      <IconBubble icon={icon} color={value ? color : C.glow} />
      <View style={{ flex: 1 }}>
        <Txt v="strong">{title}</Txt>
        <Txt v="caption" color={C.faint}>{sub}</Txt>
      </View>
      <Toggle value={value} onChange={onChange} color={color} label={title} />
    </View>
  );
}

/** A bulb in a circle; while the TV is looking, rings of light ripple out from it. */
function Beacon({ active, icon: Icon = Lightbulb }: { active: boolean; icon?: typeof Lightbulb }) {
  return (
    <View style={{ width: 150, height: 150, alignItems: 'center', justifyContent: 'center' }}>
      {[0, 1, 2].map((i) => <Ripple key={i} i={i} active={active} />)}
      <View style={styles.beacon}>
        <Icon size={34} color={C.text} strokeWidth={1.7} />
      </View>
    </View>
  );
}

function Ripple({ i, active }: { i: number; active: boolean }) {
  const reduced = useReducedMotion();
  const t = useSharedValue(0);
  useEffect(() => {
    if (!active || reduced) { t.value = withTiming(0, { duration: 300 }); return; }
    t.value = 0;
    t.value = withDelay(i * 600, withRepeat(withTiming(1, { duration: 1800, easing: Easing.out(Easing.quad) }), -1, false));
  }, [active, reduced, i, t]);
  const st = useAnimatedStyle(() => ({ opacity: (1 - t.value) * (t.value > 0 ? 0.55 : 0), transform: [{ scale: 0.6 + t.value * 1.1 }] }));
  return <Animated.View style={[styles.ripple, st]} pointerEvents="none" />;
}

function EmptyState({ discovering, searched, onDiscover }: { discovering: boolean; searched: boolean; onDiscover: () => void }) {
  return (
    <Animated.View entering={FadeInDown.delay(60).duration(450)}>
      <Glass radius={R.xl} style={styles.empty}>
        <Beacon active={discovering} />
        <Txt v="serif" style={{ textAlign: 'center' }}>{discovering ? 'Looking for lights' : 'No lights yet'}</Txt>
        <Txt v="body" color={C.dim} style={{ textAlign: 'center' }}>
          The TV looks for WiZ bulbs on the same Wi-Fi as itself. Set the bulb up in the WiZ app and switch it on first.
        </Txt>
        {searched && !discovering ? (
          <Txt v="caption" color={C.warn} style={{ textAlign: 'center' }}>Nothing turned up. Check the bulb is on, then look again.</Txt>
        ) : null}
        <Button title={discovering ? 'Looking…' : 'Look for lights'} icon={ScanSearch} busy={discovering} onPress={onDiscover}
          style={{ alignSelf: 'stretch', marginTop: 8 }} />
      </Glass>
    </Animated.View>
  );
}

function SilentCard({ discovering, onDiscover }: { discovering: boolean; onDiscover: () => void }) {
  return (
    <Animated.View entering={FadeInDown.delay(60).duration(450)}>
      <Glass radius={R.xl} style={styles.empty}>
        <Beacon active={discovering} icon={LightbulbOff} />
        <Txt v="serif" style={{ textAlign: 'center' }}>The light isn't answering</Txt>
        <Txt v="body" color={C.dim} style={{ textAlign: 'center' }}>
          Is it switched on at the wall? It needs power and the same Wi-Fi as the TV.
        </Txt>
        <Button title={discovering ? 'Looking…' : 'Look again'} icon={ScanSearch} kind="secondary" busy={discovering}
          onPress={onDiscover} style={{ alignSelf: 'stretch', marginTop: 8 }} />
      </Glass>
    </Animated.View>
  );
}

function ProblemCard({ problem, onRetry }: { problem: Problem; onRetry: () => void }) {
  const copy = {
    offline: { icon: WifiOff, title: "Can't reach the TV", body: 'Is it on and on this Wi-Fi?' },
    unsupported: { icon: LightbulbOff, title: "This TV can't do lights yet", body: 'Update the launcher on the TV, then come back.' },
    other: { icon: LightbulbOff, title: "Couldn't load the lights", body: 'Something went wrong on the TV. Try again in a moment.' },
  }[problem];
  const Icon = copy.icon;
  return (
    <Animated.View entering={FadeInDown.delay(60).duration(450)}>
      <Glass radius={R.lg} tint="rgba(255,201,121,0.08)" style={[styles.card, { marginTop: 22, gap: 6 }]}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
          <Icon size={20} color={C.warn} />
          <Txt v="strong" style={{ flex: 1 }}>{copy.title}</Txt>
        </View>
        <Txt v="body" color={C.dim}>{copy.body}</Txt>
        <Button title="Try again" kind="secondary" small onPress={onRetry} style={{ alignSelf: 'flex-start', marginTop: 8 }} />
      </Glass>
    </Animated.View>
  );
}

function Loading({ width }: { width: number }) {
  return (
    <View style={{ alignItems: 'center', marginTop: 40, gap: 18 }}>
      <Shimmer style={{ width: 148, height: 148 }} radius={74} />
      <Shimmer style={{ width: 140, height: 26 }} radius={8} />
      <Shimmer style={{ width: width - S.gutter * 2, height: 180, marginTop: 24 }} radius={R.lg} />
    </View>
  );
}

/** Blend two hex colours; `t` of the way from a to b. */
function mix(a: string, b: string, t: number) {
  const x = fromHex(a), y = fromHex(b);
  return toHex([x[0] + (y[0] - x[0]) * t, x[1] + (y[1] - x[1]) * t, x[2] + (y[2] - x[2]) * t]);
}

const styles = StyleSheet.create({
  abs: { position: 'absolute' },
  top: { paddingHorizontal: S.gutter, height: 52, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  lost: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 14 },
  card: { padding: 16 },
  cardHead: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 12 },
  section: { marginTop: 30, marginBottom: 12 },
  scene: { flexDirection: 'row', alignItems: 'center', gap: 10, height: 56, paddingHorizontal: 12 },
  orbWrap: { width: 32, height: 32, alignItems: 'center', justifyContent: 'center' },
  orb: { width: 26, height: 26, borderRadius: 13 },
  orbHalo: { position: 'absolute', width: 36, height: 36, borderRadius: 18 },
  more: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 4, paddingVertical: 14 },
  autoRow: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 16 },
  rule: { height: StyleSheet.hairlineWidth, backgroundColor: 'rgba(255,255,255,0.14)' },
  empty: { padding: 24, paddingTop: 12, alignItems: 'center', gap: 10, marginTop: 22 },
  beacon: {
    width: 84, height: 84, borderRadius: 42, alignItems: 'center', justifyContent: 'center',
    backgroundColor: 'rgba(169,184,255,0.18)', borderWidth: StyleSheet.hairlineWidth, borderColor: 'rgba(255,255,255,0.3)',
  },
  ripple: { position: 'absolute', width: 120, height: 120, borderRadius: 60, borderWidth: 1.5, borderColor: C.glow },
});
