import { useEffect, useState } from 'react';
import { StyleSheet, useWindowDimensions, View } from 'react-native';
import { router } from 'expo-router';
import Animated, {
  Easing, FadeIn, FadeInDown, useAnimatedStyle, useReducedMotion, useSharedValue, withDelay, withRepeat, withSequence, withTiming,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  ArrowLeft, ChevronDown, CircleDot, FastForward, House, Keyboard, Lightbulb, Menu, Pause, Play, Rewind, TriangleAlert, Volume1, Volume2, VolumeX, MousePointer2,
  type LucideIcon,
} from 'lucide-react-native';
import { sendKey } from '@/lib/remote';
import type { Key } from '@/lib/types';
import { useActiveTv, useClient, useStore } from '@/state/store';
import { Dot, RoundButton, Segmented } from '@/ui/Controls';
import { DPad } from '@/ui/DPad';
import { Glass } from '@/ui/Glass';
import { KeyboardSheet } from '@/ui/KeyboardSheet';
import { Banner } from '@/ui/Media';
import { Press } from '@/ui/Press';
import { Sheet } from '@/ui/Sheet';
import { Touchpad } from '@/ui/Touchpad';
import { Txt } from '@/ui/Txt';
import { C, R, S } from '@/ui/theme';

const TAB_BAR = 92;
let autoTried = false;

export default function Remote() {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const tv = useActiveTv();
  const online = useStore((s) => s.online);
  const info = useStore((s) => s.info);
  const padMode = useStore((s) => s.padMode);
  const [kb, setKb] = useState(false);
  const [switcher, setSwitcher] = useState(false);

  // Fit the pad between the header block and the buttons on any phone height.
  const avail = height - insets.top - insets.bottom - TAB_BAR - 56 /*header*/ - 84 /*now*/ - 52 /*mode*/ - 140 /*buttons*/ - 40;
  const pad = Math.max(200, Math.min(width - S.gutter * 2 - 70, avail, 270));
  const keysDown = info && info.keys !== 'ready';
  const keysStatus = useStore((s) => s.keysStatus);
  // Start the TV's key sender by ourselves (once per app run); the notice offers a retry after that.
  useEffect(() => {
    if (info?.keys === 'needs_bootstrap' && keysStatus === null && !autoTried) {
      autoTried = true;
      useStore.getState().startKeys();
    }
  }, [info?.keys, keysStatus]);

  return (
    <View style={{ flex: 1, paddingTop: insets.top + 6, paddingHorizontal: S.gutter }}>
      {/* header */}
      <View style={styles.header}>
        <Press onPress={() => setSwitcher(true)} feedback="tap" scaleTo={0.97} style={{ flexShrink: 1 }} accessibilityLabel="Switch TV">
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <Txt v="serif" numberOfLines={1} style={{ flexShrink: 1, fontSize: 26, lineHeight: 30 }}>{tv?.name ?? 'TV'}</Txt>
            <ChevronDown size={20} color={C.dim} style={{ marginTop: 6 }} />
          </View>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 2, marginLeft: -4 }}>
            <Dot color={online === false ? C.bad : online ? C.live : C.ghost} size={6} />
            <Txt v="caption" color={C.faint}>{online === false ? 'Not reachable' : online ? 'Connected' : 'Connecting'}</Txt>
          </View>
        </Press>
        <View style={{ flexDirection: 'row', gap: 10 }}>
          <RoundButton icon={Lightbulb} size={44} label="Lights" onPress={() => router.push('/lights')} feedback="tap" />
          <RoundButton icon={Keyboard} size={44} label="Type on TV" onPress={() => setKb(true)} feedback="tap" />
        </View>
      </View>

      {keysDown ? <KeysNotice /> : <NowPlayingCard />}

      <View style={{ alignItems: 'center', marginTop: 12 }}>
        <View style={{ width: 236 }}>
          <Segmented value={padMode} onChange={(m) => useStore.getState().setPadMode(m)}
            options={[{ value: 'dpad', label: 'D-pad', icon: CircleDot }, { value: 'touch', label: 'Touchpad', icon: MousePointer2 }]} />
        </View>
      </View>

      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        {padMode === 'dpad' ? (
          <Animated.View key="dpad" entering={FadeIn.duration(260)}>
            <DPad size={pad} onKey={(k, long) => sendKey(k, long)} />
          </Animated.View>
        ) : (
          <Animated.View key="touch" entering={FadeIn.duration(260)}>
            <Touchpad width={width - S.gutter * 2} height={pad} onKey={(k, long) => sendKey(k, long)} />
          </Animated.View>
        )}
      </View>

      <View style={{ paddingBottom: insets.bottom + TAB_BAR + 8, gap: 12 }}>
        <View style={styles.row}>
          <RoundButton icon={ArrowLeft} label="Back" onPress={() => sendKey('back')} />
          <RoundButton icon={House} label="Home" onPress={() => sendKey('home')} />
          <RoundButton icon={Menu} label="Menu" onPress={() => sendKey('menu')} />
        </View>
        <View style={{ flexDirection: 'row', gap: 12 }}>
          <PillGroup style={{ flex: 1.15 }} items={[
            { icon: Rewind, key: 'rewind', label: 'Rewind' },
            { icon: PlayPauseIcon(), key: 'play_pause', label: 'Play or pause', big: true },
            { icon: FastForward, key: 'fast_forward', label: 'Fast forward' },
          ]} />
          <PillGroup style={{ flex: 1 }} items={[
            { icon: Volume1, key: 'volume_down', label: 'Volume down' },
            { icon: VolumeX, key: 'mute', label: 'Mute' },
            { icon: Volume2, key: 'volume_up', label: 'Volume up' },
          ]} />
        </View>
      </View>

      <KeyboardSheet visible={kb} onClose={() => setKb(false)} />
      <TvSwitcher visible={switcher} onClose={() => setSwitcher(false)} />
    </View>
  );
}

function PlayPauseIcon(): LucideIcon {
  const playing = useStore((s) => s.now?.playing);
  return playing ? Pause : Play;
}

function PillGroup({ items, style }: { items: { icon: LucideIcon; key: Key; label: string; big?: boolean }[]; style?: object }) {
  return (
    <Glass radius={26} style={[{ flexDirection: 'row', height: 52, alignItems: 'center', paddingHorizontal: 4 }, style]}>
      {items.map(({ icon: Icon, key, label, big }) => (
        <Press key={key} onPress={() => sendKey(key)} feedback="press" scaleTo={0.86} style={styles.pillBtn}
          accessibilityRole="button" accessibilityLabel={label}>
          <Icon size={big ? 21 : 19} color={C.text} strokeWidth={1.9} fill={big ? C.text : 'transparent'} />
        </Press>
      ))}
    </Glass>
  );
}

/** What's on the TV: the app's banner, its name, and live bars while audio plays. */
function NowPlayingCard() {
  const now = useStore((s) => s.now);
  const app = useStore((s) => (s.now ? s.apps.find((a) => a.pkg === s.now!.pkg) : undefined));
  const client = useClient();
  const home = !now || !now.pkg || !now.label || now.pkg === 'dev.bhawesh.tvhome';
  return (
    <Animated.View entering={FadeInDown.duration(400)}>
      <Glass radius={R.lg} style={styles.now}>
        {home ? (
          <View style={[styles.nowArt, styles.homeArt]}><House size={22} color={C.text} strokeWidth={1.8} /></View>
        ) : (
          <Banner key={now.pkg} client={client} app={{ pkg: now.pkg, label: now.label, color: app?.color }} style={styles.nowArt} radius={10} />
        )}
        <View style={{ flex: 1 }}>
          <Txt v="caption" color={C.faint}>{home ? 'On the TV' : now.playing ? 'Playing now' : 'Open now'}</Txt>
          <Txt v="heading" numberOfLines={1}>{home ? 'Home screen' : now.label}</Txt>
        </View>
        {!home && now.playing ? <Bars /> : null}
      </Glass>
    </Animated.View>
  );
}

function Bars() {
  const reduced = useReducedMotion();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'flex-end', gap: 3, height: 18, marginRight: 4 }} accessibilityLabel="Playing">
      {[0, 1, 2, 3].map((i) => <Bar key={i} i={i} still={reduced} />)}
    </View>
  );
}

function Bar({ i, still }: { i: number; still: boolean }) {
  const h = useSharedValue(0.4);
  useEffect(() => {
    if (still) { h.value = 0.6; return; }
    const d = 380 + i * 90;
    h.value = withDelay(i * 120, withRepeat(withSequence(
      withTiming(1, { duration: d, easing: Easing.inOut(Easing.sin) }),
      withTiming(0.25, { duration: d, easing: Easing.inOut(Easing.sin) }),
    ), -1, true));
  }, [still, h, i]);
  const st = useAnimatedStyle(() => ({ height: `${h.value * 100}%` }));
  return <Animated.View style={[{ width: 3.5, borderRadius: 2, backgroundColor: C.live }, st]} />;
}

function KeysNotice() {
  const info = useStore((s) => s.info);
  const status = useStore((s) => s.keysStatus);
  const error = useStore((s) => s.keysError);
  const busy = status !== null && status !== 'error';
  const text: Record<string, string> = {
    key: 'Making this phone’s key, one time only. This takes a few seconds.',
    connecting: 'Connecting to the TV…',
    approve: 'On the TV, first tick “Always allow from this computer”, then choose OK. Without the tick it asks again every time.',
    starting: 'Starting the TV’s key sender…',
  };
  return (
    <Animated.View entering={FadeInDown.duration(400)}>
      <Glass radius={R.lg} style={[styles.now, { alignItems: 'flex-start' }]} tint="rgba(255,201,121,0.08)">
        <TriangleAlert size={22} color={status === 'approve' ? C.glow : C.warn} style={{ marginTop: 2 }} />
        <View style={{ flex: 1, gap: 2 }}>
          <Txt v="strong">{status === 'approve' ? 'Allow this phone on the TV' : busy ? 'Getting the buttons ready' : 'Buttons aren’t ready on the TV'}</Txt>
          <Txt v="caption" color={C.dim}>
            {busy ? text[status]
              : status === 'error' ? `${error} Opening apps and playing titles still work.`
              : info?.keys === 'needs_bootstrap'
                ? 'The TV restarted and its key sender needs starting. Opening apps and playing titles still work.'
                : 'Key presses aren’t available right now. Opening apps and playing titles still work.'}
          </Txt>
          {!busy && (
            <Press onPress={() => (info?.keys === 'needs_bootstrap' ? useStore.getState().startKeys() : useStore.getState().refreshInfo())}
              style={{ alignSelf: 'flex-start', marginTop: 6 }} accessibilityRole="button">
              <Txt v="label" color={C.glow}>{info?.keys === 'needs_bootstrap' ? (status === 'error' ? 'Try again' : 'Start key presses') : 'Check again'}</Txt>
            </Press>
          )}
        </View>
      </Glass>
    </Animated.View>
  );
}

function TvSwitcher({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const tvs = useStore((s) => s.tvs);
  const activeId = useStore((s) => s.activeId);
  return (
    <Sheet visible={visible} onClose={onClose} title="Your TVs">
      <View style={{ gap: 10 }}>
        {tvs.map((t) => {
          const on = t.id === activeId;
          return (
            <Press key={t.id} onPress={() => { useStore.getState().setActive(t.id); onClose(); }} scaleTo={0.97}
              accessibilityRole="button" accessibilityState={{ selected: on }}>
              <Glass radius={R.lg} tint={on ? 'rgba(169,184,255,0.2)' : undefined} style={{ padding: 16, flexDirection: 'row', alignItems: 'center', gap: 10 }}>
                <View style={{ flex: 1 }}>
                  <Txt v="heading">{t.name}</Txt>
                  <Txt v="caption" color={C.faint}>{t.host}</Txt>
                </View>
                {on ? <Dot color={C.live} size={7} /> : null}
              </Glass>
            </Press>
          );
        })}
        <Press onPress={() => { onClose(); router.push('/connect'); }} style={{ paddingVertical: 12, alignItems: 'center' }} accessibilityRole="button">
          <Txt v="strong" color={C.glow}>Add another TV</Txt>
        </Press>
      </View>
    </Sheet>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12, minHeight: 56, marginBottom: 12 },
  now: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 12, minHeight: 64 },
  nowArt: { width: 86, borderRadius: 10 },
  homeArt: { height: 48, backgroundColor: 'rgba(169,184,255,0.16)', alignItems: 'center', justifyContent: 'center' },
  row: { flexDirection: 'row', justifyContent: 'space-around' },
  pillBtn: { flex: 1, height: 46, alignItems: 'center', justifyContent: 'center' },
});
