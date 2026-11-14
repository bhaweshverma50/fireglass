import { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, useWindowDimensions, View } from 'react-native';
import { router } from 'expo-router';
import { Image } from 'expo-image';
import * as ImagePicker from 'expo-image-picker';
import Animated, { FadeIn, FadeInDown } from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ArrowLeft, Check, ImagePlus, MonitorCheck, MonitorOff, Repeat, SkipForward, Trash2 } from 'lucide-react-native';
import type { Wallpaper, WallpaperList } from '@/lib/types';
import { activeClient } from '@/state/store';
import { Button, RoundButton } from '@/ui/Controls';
import { Press } from '@/ui/Press';
import { Sheet } from '@/ui/Sheet';
import { Shimmer } from '@/ui/Shimmer';
import { toast } from '@/ui/Toast';
import { Txt } from '@/ui/Txt';
import { C, R, S } from '@/ui/theme';

export default function Wallpapers() {
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const [list, setList] = useState<WallpaperList | null>(null);
  const [failed, setFailed] = useState(false);
  const [sending, setSending] = useState<{ done: number; of: number } | null>(null);
  const [picked, setPicked] = useState<Wallpaper | null>(null);
  const client = activeClient();
  const tile = (width - S.gutter * 2 - S.gap) / 2;

  const load = useCallback(async () => {
    try {
      setList(await activeClient()!.wallpapers());
      setFailed(false);
    } catch {
      setFailed(true);
    }
  }, []);
  useEffect(() => { load(); }, [load]);

  const upload = async () => {
    const res = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'], allowsMultipleSelection: true, selectionLimit: 20, quality: 0.9, exif: false,
    });
    if (res.canceled || !res.assets.length) return;
    const tv = activeClient();
    if (!tv) return;
    let sent = 0;
    setSending({ done: 0, of: res.assets.length });
    for (const a of res.assets) {
      try {
        await tv.uploadWallpaper(a.uri);
        sent++;
      } catch { /* counted below */ }
      setSending({ done: sent, of: res.assets.length });
    }
    setSending(null);
    toast(sent === res.assets.length ? `Sent ${sent} to the TV` : `Sent ${sent} of ${res.assets.length}`,
      sent === res.assets.length ? 'info' : 'error');
    load();
  };

  const act = async (fn: () => Promise<unknown>, done: string) => {
    setPicked(null);
    try {
      await fn();
      toast(done);
    } catch {
      toast("Couldn't reach the TV", 'error');
    }
    load();
  };

  const isBg = picked != null && list?.background === picked.name;
  const rot = list?.rotation;
  const allNames = list?.items.map((w) => w.name) ?? [];
  const inRot = (w: Wallpaper) => (rot ? (rot.names == null ? true : rot.names.includes(w.name)) : false);
  const rotCount = list ? list.items.filter(inRot).length : 0;
  const pickedIn = picked != null && inRot(picked);
  const toggleRot = (w: Wallpaper) => {
    const cur = rot?.names ?? allNames;
    return pickedIn ? cur.filter((n) => n !== w.name) : [...cur.filter((n) => n !== w.name), w.name];
  };
  const quick = async (fn: () => Promise<unknown>, done?: string) => {
    try {
      await fn();
      if (done) toast(done);
    } catch {
      toast("Couldn't reach the TV", 'error');
    }
    load();
  };

  return (
    <View style={{ flex: 1, paddingTop: insets.top + 6 }}>
      <View style={{ paddingHorizontal: S.gutter, height: 52, justifyContent: 'center' }}>
        <RoundButton icon={ArrowLeft} size={44} label="Back" feedback="tap" onPress={() => router.back()} />
      </View>
      <ScrollView contentContainerStyle={{ padding: S.gutter, paddingTop: 8, paddingBottom: insets.bottom + 40 }}>
        <Animated.View entering={FadeInDown.duration(450)}>
          <Txt v="display">Wallpapers</Txt>
          <Txt v="body" color={C.dim} style={{ marginTop: 8 }}>
            Pictures on your TV. The Photos screensaver shows all of them, and any one can sit behind the home screen.
          </Txt>
        </Animated.View>

        {rot ? (
          <Animated.View entering={FadeInDown.delay(80).duration(450)} style={styles.rotCard}>
            <View style={styles.rotHead}>
              <Repeat size={16} color={C.glow} />
              <Txt v="body" style={{ flex: 1 }}>Change every</Txt>
              <Txt v="caption" color={C.faint}>{rotCount} of {allNames.length} in rotation</Txt>
            </View>
            <View style={styles.seg}>
              {EVERY.map((o) => {
                const on = rot.every === o.min;
                return (
                  <Press key={o.min} style={{ flex: 1 }} scaleTo={0.96} feedback="tap" accessibilityRole="button"
                    accessibilityState={{ selected: on }} onPress={() => !on && quick(() => client!.setRotation({ every: o.min }))}>
                    <View style={[styles.segItem, on && styles.segOn]}>
                      <Txt v="caption" color={on ? '#0B1030' : C.dim}>{o.label}</Txt>
                    </View>
                  </Press>
                );
              })}
            </View>
            <View style={styles.rotRow}>
              <View style={{ flex: 1 }}>
                <Button title="Select all" kind="secondary" small disabled={!client || rot.names == null}
                  onPress={() => quick(() => client!.setRotation({ names: null }), 'Every picture rotates')} />
              </View>
              <View style={{ flex: 1 }}>
                <Button title="None" kind="secondary" small disabled={!client || (rot.names != null && rot.names.length === 0)}
                  onPress={() => quick(() => client!.setRotation({ names: [] }), 'Rotation list cleared')} />
              </View>
            </View>
            {rot.every > 0 ? (
              <Button title="Next wallpaper" icon={SkipForward} kind="secondary" disabled={!client} style={{ marginTop: 10 }}
                onPress={() => quick(() => client!.nextWallpaper(), 'Switched to the next picture')} />
            ) : null}
          </Animated.View>
        ) : null}

        <Button title={sending ? `Sending ${Math.min(sending.done + 1, sending.of)} of ${sending.of}…` : 'Add pictures'}
          icon={ImagePlus} busy={!!sending} disabled={!client || !!sending} style={{ marginTop: 20 }} onPress={upload} />

        {failed ? (
          <Txt v="body" color={C.warn} style={{ marginTop: 22 }}>Couldn't load the TV's pictures. Is it on and on this Wi-Fi?</Txt>
        ) : null}

        <View style={styles.grid}>
          {list == null && !failed
            ? [0, 1, 2, 3].map((i) => <Shimmer key={i} style={{ width: tile, height: tile * 0.5625, borderRadius: R.md }} />)
            : null}
          {list?.items.map((w, i) => (
            <Animated.View key={w.name} entering={FadeIn.delay(Math.min(i, 12) * 30)}>
              <Press onPress={() => setPicked(w)} scaleTo={0.96} feedback="tap" accessibilityRole="button"
                accessibilityLabel={list.background === w.name ? `${w.name}, background` : w.name}>
                <View style={[styles.tile, { width: tile, height: tile * 0.5625 }, list.background === w.name && styles.on]}>
                  {client ? (
                    <Image source={client.wallpaperThumb(w.name)} style={StyleSheet.absoluteFill} contentFit="cover"
                      transition={250} cachePolicy="memory-disk" recyclingKey={w.name} />
                  ) : null}
                  {list.background === w.name ? (
                    <View style={styles.badge}><MonitorCheck size={13} color="#0B1030" /><Txt v="caption" color="#0B1030">Background</Txt></View>
                  ) : null}
                  {inRot(w) ? <View style={styles.check}><Check size={13} color="#0B1030" strokeWidth={3} /></View> : null}
                </View>
              </Press>
            </Animated.View>
          ))}
        </View>
        {list && !list.items.length ? (
          <Txt v="body" color={C.faint} style={{ marginTop: 6 }}>No pictures yet. Add some from your photos.</Txt>
        ) : null}
      </ScrollView>

      <Sheet visible={!!picked} onClose={() => setPicked(null)} title="This picture">
        {picked && client ? (
          <View style={[styles.tile, { width: '100%', aspectRatio: 16 / 9, marginBottom: 18 }]}>
            <Image source={client.wallpaperThumb(picked.name)} style={StyleSheet.absoluteFill} contentFit="cover" />
          </View>
        ) : null}
        <Button title={isBg ? 'Stop using as background' : 'Use as TV background'} icon={isBg ? MonitorOff : MonitorCheck}
          onPress={() => picked && act(() => client!.setBackground(isBg ? null : picked.name),
            isBg ? 'Background back to the sky' : 'Set as the TV background')} />
        {rot ? (
          <Button title={pickedIn ? 'Remove from rotation' : 'Add to rotation'} icon={pickedIn ? MonitorOff : Repeat} kind="secondary"
            style={{ marginTop: 10 }}
            onPress={() => picked && act(() => client!.setRotation({ names: toggleRot(picked) }),
              pickedIn ? 'Removed from rotation' : 'Added to rotation')} />
        ) : null}
        <Button title="Delete from TV" icon={Trash2} kind="danger" style={{ marginTop: 10 }}
          onPress={() => picked && act(() => client!.deleteWallpaper(picked.name), 'Deleted from the TV')} />
      </Sheet>
    </View>
  );
}

const EVERY = [
  { min: 0, label: 'Never' },
  { min: 15, label: '15 min' },
  { min: 60, label: 'Hourly' },
  { min: 1440, label: 'Daily' },
];

const styles = StyleSheet.create({
  rotCard: {
    marginTop: 20, padding: 14, borderRadius: R.md, backgroundColor: C.fill,
    borderWidth: StyleSheet.hairlineWidth, borderColor: C.hair,
  },
  rotHead: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 12 },
  seg: { flexDirection: 'row', padding: 4, borderRadius: R.pill, backgroundColor: C.fill, gap: 4 },
  segItem: { height: 36, borderRadius: R.pill, alignItems: 'center', justifyContent: 'center' },
  segOn: { backgroundColor: C.glow },
  rotRow: { flexDirection: 'row', gap: 10, marginTop: 12 },
  check: {
    position: 'absolute', right: 8, top: 8, width: 22, height: 22, borderRadius: 11,
    backgroundColor: C.live, alignItems: 'center', justifyContent: 'center',
  },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: S.gap, marginTop: 22 },
  tile: { borderRadius: R.md, overflow: 'hidden', backgroundColor: C.fill, borderWidth: StyleSheet.hairlineWidth, borderColor: C.hair },
  on: { borderWidth: 2, borderColor: C.glow },
  badge: {
    position: 'absolute', left: 8, top: 8, flexDirection: 'row', alignItems: 'center', gap: 4,
    paddingHorizontal: 8, height: 24, borderRadius: 12, backgroundColor: C.glow,
  },
});
