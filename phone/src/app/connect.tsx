import { useEffect, useRef, useState, type ComponentRef } from 'react';
import { ActivityIndicator, Keyboard, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { router } from 'expo-router';
import * as Device from 'expo-device';
import Animated, {
  FadeIn, FadeInDown, FadeOut, LinearTransition, useAnimatedStyle, useSharedValue, withSequence, withTiming,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ArrowLeft, ChevronRight, RefreshCw, Tv, Wifi } from 'lucide-react-native';
import { ApiError, DEFAULT_PORT, parseAddress, TvClient } from '@/lib/api';
import { haptic } from '@/lib/haptics';
import { useTvDiscovery, type FoundTv } from '@/native/discovery';
import { tvId, useStore } from '@/state/store';
import { Button, RoundButton } from '@/ui/Controls';
import { Glass } from '@/ui/Glass';
import { Press } from '@/ui/Press';
import { Shimmer } from '@/ui/Shimmer';
import { Txt } from '@/ui/Txt';
import { C, F, R, S } from '@/ui/theme';

type Target = { name: string; host: string; port: number };

export default function Connect() {
  const insets = useSafeAreaInsets();
  const saved = useStore((s) => s.tvs);
  const [target, setTarget] = useState<Target | null>(null);
  const { tvs, scanning, error, restart } = useTvDiscovery(!target);

  return (
    <View style={{ flex: 1, paddingTop: insets.top + 8 }}>
      {saved.length > 0 || target ? (
        <View style={{ paddingHorizontal: S.gutter, height: 48, justifyContent: 'center' }}>
          <RoundButton icon={ArrowLeft} size={44} label="Back" feedback="tap"
            onPress={() => (target ? setTarget(null) : router.back())} />
        </View>
      ) : <View style={{ height: 48 }} />}
      {target ? (
        <Pair key={target.host} target={target} onDone={() => router.replace('/remote')} />
      ) : (
        <Find tvs={tvs} scanning={scanning} error={error} restart={restart} onPick={(t) => {
          const known = useStore.getState().tvs.find((x) => x.id === tvId(t.host, t.port));
          if (known) { useStore.getState().setActive(known.id); router.replace('/remote'); return; }
          setTarget(t);
        }} />
      )}
    </View>
  );
}

function Find({ tvs, scanning, error, restart, onPick }: {
  tvs: FoundTv[]; scanning: boolean; error: string | null; restart: () => void; onPick: (t: Target) => void;
}) {
  const insets = useSafeAreaInsets();
  const [addr, setAddr] = useState('');
  const [checking, setChecking] = useState(false);
  const [addrError, setAddrError] = useState<string | null>(null);
  const saved = useStore((s) => s.tvs);

  const manual = async () => {
    const a = parseAddress(addr);
    if (!a) { setAddrError('That doesn’t look like an address. Try something like 192.168.0.2'); haptic.err(); return; }
    setChecking(true);
    setAddrError(null);
    try {
      // Any answer (even "unpaired") means a TV is listening there.
      await new TvClient(a.host, a.port).info(4000).catch((e) => { if (!(e instanceof ApiError) || e.status === 0) throw e; });
      Keyboard.dismiss();
      onPick({ name: 'TV', ...a });
    } catch (e) {
      haptic.err();
      setAddrError(`No TV answered at ${a.host}:${a.port}. Check it’s on and on the same Wi-Fi.`);
    } finally {
      setChecking(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={{ padding: S.gutter, paddingBottom: insets.bottom + 40 }} keyboardShouldPersistTaps="handled">
      <Animated.View entering={FadeInDown.duration(500)}>
        <Txt v="display">Find your TV</Txt>
        <Txt v="body" color={C.dim} style={{ marginTop: 8, maxWidth: 320 }}>
          Your phone and the TV need to be on the same Wi-Fi. The TV shows up here on its own.
        </Txt>
      </Animated.View>

      <View style={{ marginTop: 28, gap: 12 }}>
        <View style={styles.rowBetween}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            {scanning ? <ActivityIndicator size="small" color={C.glow} /> : <Wifi size={16} color={C.dim} />}
            <Txt v="label" color={C.dim}>{scanning ? 'Looking on this Wi-Fi' : 'On this Wi-Fi'}</Txt>
          </View>
          <Press onPress={restart} hitSlop={10} accessibilityLabel="Look again">
            <RefreshCw size={18} color={C.dim} />
          </Press>
        </View>

        {tvs.map((t, i) => {
          const paired = saved.some((s) => s.id === tvId(t.host, t.port));
          return (
            <Animated.View key={`${t.host}:${t.port}`} entering={FadeInDown.delay(i * 80).springify().damping(18)} layout={LinearTransition}>
              <Press onPress={() => onPick(t)} scaleTo={0.97} feedback="press" accessibilityRole="button" accessibilityLabel={`Connect to ${t.name}`}>
                <Glass radius={R.lg} style={styles.tvRow}>
                  <View style={styles.tvIcon}><Tv size={22} color={C.text} strokeWidth={1.8} /></View>
                  <View style={{ flex: 1 }}>
                    <Txt v="heading" numberOfLines={1}>{t.name}</Txt>
                    <Txt v="caption" color={C.faint}>{paired ? 'Paired' : `${t.host}${t.port !== DEFAULT_PORT ? `:${t.port}` : ''}`}</Txt>
                  </View>
                  <ChevronRight size={20} color={C.dim} />
                </Glass>
              </Press>
            </Animated.View>
          );
        })}
        {!tvs.length ? (
          scanning ? <Shimmer style={{ height: 76 }} radius={R.lg} /> : (
            <Glass radius={R.lg} style={{ padding: 18 }}>
              <Txt v="body" color={C.dim}>
                {error ? `Couldn’t look for TVs (${error}).` : 'No TVs found yet. Make sure the TV is on and showing its home screen, or type its address below.'}
              </Txt>
            </Glass>
          )
        ) : null}
      </View>

      <View style={{ marginTop: 32 }}>
        <Txt v="label" color={C.dim} style={{ marginBottom: 10 }}>Or type its address</Txt>
        <Glass radius={R.lg} style={styles.inputWrap}>
          <TextInput
            value={addr} onChangeText={(v) => { setAddr(v); setAddrError(null); }}
            placeholder="192.168.0.2" placeholderTextColor={C.ghost}
            style={styles.input} keyboardType="numbers-and-punctuation" autoCapitalize="none" autoCorrect={false}
            returnKeyType="go" onSubmitEditing={manual} selectionColor={C.glow} accessibilityLabel="TV address"
          />
        </Glass>
        {addrError ? (
          <Animated.View entering={FadeIn} exiting={FadeOut}>
            <Txt v="caption" color={C.bad} style={{ marginTop: 8 }}>{addrError}</Txt>
          </Animated.View>
        ) : (
          <Txt v="caption" color={C.faint} style={{ marginTop: 8 }}>
            On the TV: Settings, My Fire TV, About, Network shows the IP address.
          </Txt>
        )}
        <Button title="Connect" onPress={manual} busy={checking} disabled={!addr.trim()} style={{ marginTop: 16 }} />
      </View>
    </ScrollView>
  );
}

/** retry_after_s as "30 s" or "4 min". */
const wait = (s?: number) => (!s ? 'a moment' : s < 60 ? `${s} s` : `${Math.ceil(s / 60)} min`);

function Pair({ target, onDone }: { target: Target; onDone: () => void }) {
  const [phase, setPhase] = useState<'asking' | 'pin' | 'checking' | 'dropped' | 'error'>('asking');
  const [pin, setPin] = useState('');
  const [msg, setMsg] = useState<string | null>(null);
  const input = useRef<ComponentRef<typeof TextInput>>(null);
  const client = useRef(new TvClient(target.host, target.port)).current;
  const shake = useSharedValue(0);
  const shakeStyle = useAnimatedStyle(() => ({ transform: [{ translateX: shake.value }] }));

  const ask = async () => {
    setPhase('asking');
    setMsg(null);
    try {
      await client.pairRequest(Device.deviceName ?? Device.modelName ?? 'Phone');
      setPhase('pin');
      setTimeout(() => input.current?.focus(), 250);
    } catch (e) {
      setPhase('error');
      setMsg(!(e instanceof ApiError) ? 'The TV didn’t start pairing. Try again.'
        : e.status === 0 ? `Can’t reach ${target.host}. Is the TV on?`
        : e.code === 'pair_cooldown' ? `Too many tries. The TV will show a new PIN in ${wait(e.data?.retry_after_s)}.`
        : 'The TV didn’t start pairing. Try again.');
    }
  };
  useEffect(() => { ask(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const confirm = async (code: string) => {
    setPhase('checking');
    try {
      const r = await client.pairConfirm(code);
      haptic.ok();
      await useStore.getState().addTv({ name: r.name || target.name, host: target.host, port: target.port }, r.token);
      onDone();
    } catch (e) {
      haptic.err();
      shake.value = withSequence(...[10, -10, 8, -8, 4, 0].map((v) => withTiming(v, { duration: 50 })));
      setPin('');
      const left: number | undefined = e instanceof ApiError ? e.data?.tries_left : undefined;
      // The TV dropped the PIN (out of tries or timed out): typing more can't work, so ask for a new one.
      if (e instanceof ApiError && (e.code === 'pin_expired' || left === 0)) {
        setPhase('dropped');
        setMsg(left === 0 ? 'That PIN didn’t match either, so the TV dropped it. Ask for a new PIN.' : 'That PIN has run out. Ask the TV for a new one.');
        return;
      }
      setPhase('pin');
      setMsg(e instanceof ApiError && e.code === 'bad_pin'
        ? `That PIN didn’t match. Check the TV and try again${left ? ` (${left} ${left === 1 ? 'try' : 'tries'} left)` : ''}.`
        : 'Pairing failed. Ask the TV for a new PIN.');
      setTimeout(() => input.current?.focus(), 100);
    }
  };

  return (
    <View style={{ padding: S.gutter }}>
      <Animated.View entering={FadeInDown.duration(450)}>
        <Txt v="display">Pair with{'\n'}{target.name === 'TV' ? 'your TV' : target.name}</Txt>
        <Txt v="body" color={C.dim} style={{ marginTop: 10, maxWidth: 330 }}>
          {phase === 'asking' ? `Asking ${target.host} for a PIN…` : 'Type the 4-digit PIN showing on your TV. It’s there for 2 minutes.'}
        </Txt>
      </Animated.View>

      <Press onPress={() => input.current?.focus()} feedback="none" style={{ marginTop: 34 }} accessibilityLabel="PIN">
        <Animated.View style={[styles.pinRow, shakeStyle]}>
          {[0, 1, 2, 3].map((i) => {
            const filled = i < pin.length;
            const active = phase === 'pin' && i === pin.length;
            return (
              <Glass key={i} radius={R.md} style={[styles.pinBox, active && styles.pinActive]}
                tint={active ? 'rgba(169,184,255,0.16)' : undefined}>
                {filled ? (
                  <Animated.View entering={FadeIn.duration(140)}>
                    <Txt style={{ fontFamily: F.serif, fontSize: 44, lineHeight: 52 }}>{pin[i]}</Txt>
                  </Animated.View>
                ) : null}
              </Glass>
            );
          })}
        </Animated.View>
      </Press>
      <TextInput
        ref={input} value={pin} maxLength={4} keyboardType="number-pad" style={styles.hidden} autoComplete="one-time-code"
        onChangeText={(v) => {
          const d = v.replace(/\D/g, '').slice(0, 4);
          if (d.length > pin.length) haptic.tick();
          setPin(d);
          setMsg(null);
          if (d.length === 4) confirm(d);
        }}
        editable={phase === 'pin'}
      />

      <View style={{ minHeight: 56, marginTop: 18 }}>
        {phase === 'checking' || phase === 'asking' ? <ActivityIndicator color={C.glow} /> : null}
        {msg ? <Animated.View entering={FadeIn}><Txt v="body" color={phase === 'error' ? C.bad : C.warn}>{msg}</Txt></Animated.View> : null}
      </View>

      <Button title={phase === 'error' ? 'Try again' : 'Show a new PIN'} kind="secondary" onPress={ask}
        disabled={phase === 'asking' || phase === 'checking'} />
    </View>
  );
}

const styles = StyleSheet.create({
  rowBetween: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  tvRow: { flexDirection: 'row', alignItems: 'center', gap: 14, padding: 16 },
  tvIcon: { width: 46, height: 46, borderRadius: 23, backgroundColor: 'rgba(169,184,255,0.18)', alignItems: 'center', justifyContent: 'center' },
  inputWrap: { height: 56, justifyContent: 'center', paddingHorizontal: 18 },
  input: { color: C.text, fontFamily: F.medium, fontSize: 18, padding: 0 },
  pinRow: { flexDirection: 'row', gap: 12, justifyContent: 'center' },
  pinBox: { width: 68, height: 84, alignItems: 'center', justifyContent: 'center' },
  pinActive: { shadowColor: C.glow, shadowOpacity: 0.6, shadowRadius: 14, shadowOffset: { width: 0, height: 0 } },
  hidden: { position: 'absolute', opacity: 0, height: 1, width: 1 },
});
