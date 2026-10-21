import { useState } from 'react';
import { ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { router } from 'expo-router';
import Constants from 'expo-constants';
import Animated, { FadeInDown, LinearTransition } from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ChevronRight, Cpu, Image as ImageIcon, Info, Lightbulb, Pencil, Plus, Trash2, Tv } from 'lucide-react-native';
import { modelById } from '@/lib/models';
import { useAssistant } from '@/state/assistant';
import { useStore, type SavedTv } from '@/state/store';
import { Button, Dot } from '@/ui/Controls';
import { Glass } from '@/ui/Glass';
import { Press } from '@/ui/Press';
import { Sheet } from '@/ui/Sheet';
import { Txt } from '@/ui/Txt';
import { C, F, R, S } from '@/ui/theme';

export default function Settings() {
  const insets = useSafeAreaInsets();
  const tvs = useStore((s) => s.tvs);
  const activeId = useStore((s) => s.activeId);
  const modelId = useStore((s) => s.modelId);
  const status = useAssistant((s) => s.modelStatus);
  const info = useStore((s) => s.info);
  const [renaming, setRenaming] = useState<SavedTv | null>(null);
  const [forgetting, setForgetting] = useState<SavedTv | null>(null);
  const [name, setName] = useState('');
  const spec = modelById(modelId);

  return (
    <ScrollView contentContainerStyle={{ paddingTop: insets.top + 6, paddingHorizontal: S.gutter, paddingBottom: insets.bottom + 120 }}>
      <Txt v="display">Settings</Txt>

      <Txt v="serif" style={styles.section}>Your TVs</Txt>
      <View style={{ gap: 10 }}>
        {tvs.map((t, i) => {
          const on = t.id === activeId;
          return (
            <Animated.View key={t.id} entering={FadeInDown.delay(i * 60)} layout={LinearTransition}>
              <Glass radius={R.lg} tint={on ? 'rgba(169,184,255,0.14)' : undefined} style={{ padding: 16 }}>
                <Press onPress={() => useStore.getState().setActive(t.id)} feedback="tick" scaleTo={0.98}
                  style={styles.tvRow} accessibilityRole="button" accessibilityState={{ selected: on }}>
                  <View style={[styles.icon, on && { backgroundColor: 'rgba(126,231,201,0.2)' }]}>
                    <Tv size={20} color={C.text} strokeWidth={1.9} />
                  </View>
                  <View style={{ flex: 1 }}>
                    <Txt v="heading" numberOfLines={1}>{t.name}</Txt>
                    <Txt v="caption" color={C.faint}>
                      {t.host}{on && info ? ` · ${info.model}` : ''}
                    </Txt>
                  </View>
                  {on ? <View style={styles.inUse}><Dot color={C.live} size={6} /><Txt v="caption" color={C.live}>In use</Txt></View> : null}
                </Press>
                <View style={styles.tvActions}>
                  <Press onPress={() => { setName(t.name); setRenaming(t); }} style={styles.action} accessibilityRole="button">
                    <Pencil size={15} color={C.dim} /><Txt v="label" color={C.dim}>Rename</Txt>
                  </Press>
                  <Press onPress={() => setForgetting(t)} style={styles.action} accessibilityRole="button">
                    <Trash2 size={15} color={C.bad} /><Txt v="label" color={C.bad}>Forget</Txt>
                  </Press>
                </View>
              </Glass>
            </Animated.View>
          );
        })}
        <Row icon={Plus} title="Add a TV" onPress={() => router.push('/connect')} />
      </View>

      <Txt v="serif" style={styles.section}>On the TV</Txt>
      <View style={{ gap: 10 }}>
        <Row icon={ImageIcon} title="Wallpapers" sub="Your pictures for the screensaver and the home background"
          onPress={() => router.push('/wallpapers')} />
        <Row icon={Lightbulb} title="Lights" sub="Your WiZ light: brightness, colour and scenes"
          onPress={() => router.push('/lights')} />
      </View>

      <Txt v="serif" style={styles.section}>Assistant</Txt>
      <Row icon={Cpu} title="Models" onPress={() => router.push('/models')}
        sub={spec ? `${spec.name}${status === 'ready' ? ', loaded' : ''}` : 'None yet, simple commands only'} />

      <Txt v="serif" style={styles.section}>About</Txt>
      <Glass radius={R.lg} style={{ padding: 18, gap: 10 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
          <Info size={18} color={C.dim} />
          <Txt v="heading">TV Home Remote {Constants.expoConfig?.version ?? ''}</Txt>
        </View>
        <Txt v="body" color={C.dim}>
          A remote for the TV Home launcher. It talks to your TV over your Wi-Fi only, and the assistant runs on this phone.
        </Txt>
        <Txt v="caption" color={C.faint}>
          Type: Instrument Serif and Figtree (SIL Open Font License). Models: Qwen2.5 (Apache 2.0), LFM2.5 (LFM Open License 1.0),
          run with llama.cpp through llama.rn. Posters and show details come from your TV.
        </Txt>
      </Glass>

      <Sheet visible={!!renaming} onClose={() => setRenaming(null)} title="Rename TV">
        <Glass radius={R.lg} style={{ height: 56, justifyContent: 'center', paddingHorizontal: 18 }}>
          <TextInput value={name} onChangeText={setName} autoFocus style={styles.input} selectionColor={C.glow}
            placeholder="Living room" placeholderTextColor={C.ghost} returnKeyType="done" maxLength={40}
            onSubmitEditing={() => { if (renaming) useStore.getState().renameTv(renaming.id, name); setRenaming(null); }} />
        </Glass>
        <Button title="Save" style={{ marginTop: 16 }} disabled={!name.trim()}
          onPress={() => { if (renaming) useStore.getState().renameTv(renaming.id, name); setRenaming(null); }} />
      </Sheet>

      <Sheet visible={!!forgetting} onClose={() => setForgetting(null)} title="Forget this TV?">
        <Txt v="body" color={C.dim}>
          {forgetting ? `This phone stops controlling ${forgetting.name}. To use it again you’ll pair with a new PIN.` : ''}
        </Txt>
        <View style={{ flexDirection: 'row', gap: 10, marginTop: 20 }}>
          <Button title="Keep" kind="secondary" style={{ flex: 1 }} onPress={() => setForgetting(null)} />
          <Button title="Forget" kind="danger" icon={Trash2} style={{ flex: 1 }} onPress={async () => {
            const t = forgetting!;
            setForgetting(null);
            await useStore.getState().forgetTv(t.id);
            if (!useStore.getState().tvs.length) router.replace('/connect');
          }} />
        </View>
      </Sheet>
    </ScrollView>
  );
}

function Row({ icon: Icon, title, sub, onPress }: { icon: typeof Tv; title: string; sub?: string; onPress: () => void }) {
  return (
    <Press onPress={onPress} scaleTo={0.97} feedback="tap" accessibilityRole="button">
      <Glass radius={R.lg} style={styles.row}>
        <View style={styles.icon}><Icon size={20} color={C.text} strokeWidth={1.9} /></View>
        <View style={{ flex: 1 }}>
          <Txt v="heading">{title}</Txt>
          {sub ? <Txt v="caption" color={C.faint}>{sub}</Txt> : null}
        </View>
        <ChevronRight size={20} color={C.dim} />
      </Glass>
    </Press>
  );
}

const styles = StyleSheet.create({
  section: { marginTop: 30, marginBottom: 12 },
  tvRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  tvActions: { flexDirection: 'row', gap: 22, marginTop: 14, paddingTop: 12, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: 'rgba(255,255,255,0.14)' },
  action: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  icon: { width: 42, height: 42, borderRadius: 21, backgroundColor: 'rgba(169,184,255,0.18)', alignItems: 'center', justifyContent: 'center' },
  inUse: { flexDirection: 'row', alignItems: 'center', gap: 2 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 16 },
  input: { color: C.text, fontFamily: F.medium, fontSize: 18, padding: 0 },
});
