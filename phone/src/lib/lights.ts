import type { Bulb, LightScene, Lights, LightsPatch, Rgb } from './types';

export const DIM_MIN = 10;
export const DIM_MAX = 100;
export const TEMP_MIN = 2200;
export const TEMP_MAX = 6500;

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

export const toHex = ([r, g, b]: Rgb) =>
  `#${[r, g, b].map((v) => Math.round(clamp(v, 0, 255)).toString(16).padStart(2, '0')).join('')}`;

export function fromHex(hex: string): Rgb {
  const n = parseInt(hex.replace('#', '').slice(0, 6), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** Blackbody colour of a white temperature (Tanner Helland's fit), as the eye roughly sees the bulb. */
export function tempToRgb(kelvin: number): Rgb {
  const t = clamp(kelvin, 1000, 40000) / 100;
  const r = t <= 66 ? 255 : 329.698727446 * Math.pow(t - 60, -0.1332047592);
  const g = t <= 66 ? 99.4708025861 * Math.log(t) - 161.1195681661 : 288.1221695283 * Math.pow(t - 60, -0.0755148492);
  const b = t >= 66 ? 255 : t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
  return [clamp(r, 0, 255), clamp(g, 0, 255), clamp(b, 0, 255)].map(Math.round) as Rgb;
}

/** Stops for the warm-to-cool slider track. */
export const TEMP_STOPS = [2200, 2700, 3400, 4200, 5200, 6500].map((k) => toHex(tempToRgb(k)));

export type Swatch = { name: string; rgb: Rgb };
export const SWATCHES: Swatch[] = [
  { name: 'Warm white', rgb: [255, 178, 102] },
  { name: 'Amber', rgb: [255, 196, 60] },
  { name: 'Peach', rgb: [255, 128, 84] },
  { name: 'Rose', rgb: [255, 76, 122] },
  { name: 'Magenta', rgb: [222, 64, 255] },
  { name: 'Violet', rgb: [132, 92, 255] },
  { name: 'Blue', rgb: [58, 120, 255] },
  { name: 'Sky', rgb: [44, 206, 255] },
  { name: 'Mint', rgb: [64, 240, 176] },
  { name: 'Green', rgb: [140, 240, 70] },
];

const sameRgb = (a: Rgb | null, b: Rgb) => !!a && a[0] === b[0] && a[1] === b[1] && a[2] === b[2];
export const swatchFor = (rgb: Rgb | null) => SWATCHES.find((s) => sameRgb(rgb, s.rgb)) ?? null;

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '');

/** Shown first, in this order, when the TV knows them. */
const CURATED = ['Cozy', 'Warm white', 'Daylight', 'Relax', 'TV time', 'Candlelight', 'Sunset', 'Ocean', 'Party', 'Night light', 'Focus', 'Fireplace'].map(norm);

export function orderScenes(scenes: LightScene[]): { top: LightScene[]; rest: LightScene[] } {
  const rank = (s: LightScene) => CURATED.indexOf(norm(s.name));
  const top = scenes.filter((s) => rank(s) >= 0).sort((a, b) => rank(a) - rank(b));
  const rest = scenes.filter((s) => rank(s) < 0);
  return { top, rest };
}

/** Two colours that suggest each WiZ scene, for its little orb. */
const SCENE_COLORS: Record<string, [string, string]> = {
  ocean: ['#2C7BE5', '#38D9C9'], romance: ['#FF4F7B', '#B04CFF'], sunset: ['#FF7A3D', '#FF3F6C'],
  party: ['#FF3FD2', '#4F7BFF'], fireplace: ['#FF7A1A', '#FFB347'], cozy: ['#FF9A4D', '#FFC27A'],
  forest: ['#2FA35B', '#9BE15D'], pastelcolors: ['#FFB3D9', '#A8D8FF'], wakeup: ['#FFC979', '#FFF1D6'],
  bedtime: ['#FF9F5A', '#7A4DFF'], warmwhite: ['#FFB46B', '#FFD9A8'], daylight: ['#FFF4E0', '#CFE3FF'],
  coolwhite: ['#E6F0FF', '#B8D4FF'], nightlight: ['#FF8A3D', '#6B3A12'], focus: ['#F2F7FF', '#A9C8FF'],
  relax: ['#FFB070', '#FFD8B0'], truecolors: ['#FFE7C2', '#FFFFFF'], tvtime: ['#7A5CFF', '#3A7BFF'],
  plantgrowth: ['#FF4FA3', '#6B4DFF'], spring: ['#9BE15D', '#FFD1E8'], summer: ['#FFD23F', '#FF8A3D'],
  fall: ['#FF7A1A', '#C2410C'], deepdive: ['#1E3A8A', '#22D3EE'], jungle: ['#16A34A', '#A3E635'],
  mojito: ['#84CC16', '#22D3EE'], club: ['#D946EF', '#3B82F6'], christmas: ['#EF4444', '#22C55E'],
  halloween: ['#F97316', '#7C3AED'], candlelight: ['#FF9A3D', '#FFCB7A'], goldenwhite: ['#FFC46B', '#FFE6B0'],
  pulse: ['#60A5FA', '#F472B6'], steampunk: ['#B45309', '#F59E0B'], rhythm: ['#F472B6', '#818CF8'],
};
export const sceneColors = (name: string): [string, string] => SCENE_COLORS[norm(name)] ?? ['#A9B8FF', '#7E8CF2'];

/** The bulb the screen shows: the first that answers, else the first known. */
export const leadBulb = (l: Lights | null): Bulb | null => l?.bulbs.find((b) => b.online) ?? l?.bulbs[0] ?? null;

/** The colour the bulb is putting out right now, or null when it's off or silent. */
export function bulbHex(b: Bulb | null, scenes: LightScene[]): string | null {
  if (!b || !b.online || !b.on) return null;
  if (b.rgb) return toHex(b.rgb);
  if (b.temp) return toHex(tempToRgb(b.temp));
  if (b.scene != null) return sceneColors(scenes.find((s) => s.id === b.scene)?.name ?? '')[0];
  return toHex(tempToRgb(2700));
}

/** Later wins; a new mode (white, colour or scene) replaces the others. */
export function mergePatch(a: LightsPatch, b: LightsPatch): LightsPatch {
  const out = { ...a };
  if (b.temp != null || b.rgb != null || b.scene != null) { delete out.temp; delete out.rgb; delete out.scene; }
  return { ...out, ...b };
}

/** What the TV should answer with, applied ahead of it so the screen reacts at once. */
export function applyPatch(l: Lights, p: LightsPatch): Lights {
  return {
    ...l,
    bulbs: l.bulbs.map((b) => {
      if (!b.online) return b;
      const n = { ...b };
      if (p.dimming != null) n.dimming = p.dimming;
      if (p.temp != null) { n.temp = p.temp; n.rgb = null; n.scene = null; }
      if (p.rgb != null) { n.rgb = p.rgb; n.temp = null; n.scene = null; }
      if (p.scene != null) { n.scene = p.scene; n.temp = null; n.rgb = null; }
      // A WiZ bulb lights up when told any colour or brightness.
      if (p.dimming != null || p.temp != null || p.rgb != null || p.scene != null) n.on = true;
      if (p.on != null) n.on = p.on;
      return n;
    }),
  };
}

/** A short name for what the bulb is doing. */
export function describe(b: Bulb, scenes: LightScene[]): string {
  if (!b.on) return 'Off';
  if (b.scene != null) return scenes.find((s) => s.id === b.scene)?.name ?? 'Scene';
  if (b.rgb) return swatchFor(b.rgb)?.name ?? 'Colour';
  if (b.temp) return b.temp < 3200 ? 'Warm white' : b.temp < 4800 ? 'Neutral white' : 'Cool white';
  return 'On';
}
