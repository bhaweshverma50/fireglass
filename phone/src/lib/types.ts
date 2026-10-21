// Shapes from docs/remote-api.md (v1). Keep in step with the contract.

export const KEYS = [
  'up', 'down', 'left', 'right', 'select', 'back', 'home', 'menu',
  'play_pause', 'rewind', 'fast_forward', 'next', 'previous',
  'volume_up', 'volume_down', 'mute',
] as const;
export type Key = (typeof KEYS)[number];

export type TvInfo = {
  name: string;
  model: string;
  version: string;
  /** 'needs_bootstrap': the phone must start the TV's key sender over adb (see docs/remote-api.md). */
  keys: 'ready' | 'needs_bootstrap' | (string & {});
};

export type NowPlaying = { pkg: string; label: string; playing: boolean };

export type TvApp = { pkg: string; label: string; color?: string; pinned?: boolean };

export type Offer = { pkg: string; label: string; url?: string };

export type Title = {
  id: string;
  title: string;
  year?: number;
  kind?: 'tv' | 'movie' | string;
  overview?: string;
  rating?: number;
  genres?: string[];
  poster?: string;
  backdrop?: string;
  offers?: Offer[];
  /** Watch history only: the app it was watched on, the episode if the app said, and when. */
  watchedPkg?: string;
  episode?: string;
  at?: number;
};

/** /api/wallpapers: pictures on the TV, newest first; `background` is the one behind the home, if any. */
export type Wallpaper = { name: string; bytes: number; modified: number; uploaded: boolean };
export type WallpaperList = { items: Wallpaper[]; background: string | null; look: number };

/**
 * /api/lights: the WiZ bulbs the TV found on its Wi-Fi. One of temp (white), rgb (colour) or scene
 * is set while a bulb is on; an offline bulb has every state field null.
 */
export type Rgb = [number, number, number];
export type Bulb = {
  ip: string;
  mac: string | null;
  online: boolean;
  on: boolean | null;
  /** 10–100 */
  dimming: number | null;
  /** Kelvin, 2200–6500, in white mode */
  temp: number | null;
  rgb: Rgb | null;
  /** WiZ scene id while a scene runs */
  scene: number | null;
};
export type LightScene = { id: number; name: string };
export type LightsAuto = { dimWhilePlaying: boolean; matchHome: boolean };
export type Lights = { bulbs: Bulb[]; scenes: LightScene[]; auto: LightsAuto };
/** Any subset; the TV applies it to every bulb. */
export type LightsPatch = Partial<{ on: boolean; dimming: number; temp: number; rgb: Rgb; scene: number }>;

/** /api/recent items are titles or bare apps. */
export type RecentItem = Title | { pkg: string; label: string };

export const isTitle = (x: RecentItem): x is Title => typeof (x as Title).id === 'string' && typeof (x as Title).title === 'string';

export type Ok = { ok: true } | { ok: false; error: string };

export type PlayRequest = { title: string | null; pkg: string | null; kind: string | null; id: string | null };

export type PlayResult =
  | { ok: true; opened: { title: string; pkg: string; how: 'deeplink' | 'search' | 'app' } }
  | { ok: false; error: 'not_found' | 'not_on_your_apps' | string; candidates?: Title[] };

/** One of the assistant's grammar-constrained outputs. */
export type AssistantAction =
  | { action: 'play'; title: string; app?: string | null }
  | { action: 'launch'; app: string }
  | { action: 'keys'; keys: Key[] }
  | { action: 'type'; text: string; submit: boolean }
  | { action: 'search'; query: string }
  | { action: 'continue'; title?: string | null; app?: string | null }
  | { action: 'reply'; text: string };
