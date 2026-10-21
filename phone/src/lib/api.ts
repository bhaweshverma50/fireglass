import * as FileSystem from 'expo-file-system/legacy';
import type {
  Key, Lights, LightsAuto, LightsPatch, NowPlaying, Ok, PlayRequest, PlayResult, RecentItem, Title, TvApp, TvInfo, WallpaperList,
} from './types';

export const DEFAULT_PORT = 8090;

export class ApiError extends Error {
  /** `data` is the error body, for fields beyond `error` (retry_after_s, tries_left). */
  constructor(public status: number, public code: string, message?: string, public data: any = null) {
    super(message ?? code);
  }
  get unpaired() { return this.status === 401; }
}

export function baseUrl(host: string, port = DEFAULT_PORT) {
  const h = host.includes(':') && !host.startsWith('[') ? `[${host}]` : host; // bare IPv6
  return `http://${h}:${port}`;
}

/** Accepts "192.168.0.2", "192.168.0.2:8090" or "http://192.168.0.2:8090/". */
export function parseAddress(input: string): { host: string; port: number } | null {
  let s = input.trim().replace(/^https?:\/\//i, '').replace(/\/.*$/, '');
  if (!s) return null;
  let port = DEFAULT_PORT;
  const m = s.match(/^(.*):(\d{1,5})$/);
  if (m && !m[1].includes(':')) { s = m[1]; port = Number(m[2]); }
  if (!/^[a-z0-9.\-]+$/i.test(s)) return null;
  if (port < 1 || port > 65535) return null;
  return { host: s, port };
}

type Opts = { method?: 'GET' | 'POST' | 'DELETE'; body?: unknown; timeout?: number; auth?: boolean };

export class TvClient {
  constructor(public host: string, public port: number = DEFAULT_PORT, public token: string | null = null) {}

  get base() { return baseUrl(this.host, this.port); }

  /** URL plus the headers an image component needs to load a banner. */
  bannerSource(pkg: string) {
    return {
      uri: `${this.base}/api/apps/${encodeURIComponent(pkg)}/banner.png`,
      headers: this.token ? { Authorization: `Bearer ${this.token}` } : undefined,
      cacheKey: `${this.host}:${this.port}/banner/${pkg}`,
    };
  }

  private async req<T>(path: string, opts: Opts = {}): Promise<T> {
    try {
      return await this.once<T>(path, opts);
    } catch (e) {
      // A TV waking from its screensaver or sleep can miss the first request: try once more.
      if (!(e instanceof ApiError) || e.code !== 'offline') throw e;
      await new Promise((r) => setTimeout(r, 1200));
      return this.once<T>(path, opts);
    }
  }

  private async once<T>(path: string, { method = 'GET', body, timeout = 6000, auth = true }: Opts = {}): Promise<T> {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), timeout);
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (auth && this.token) headers.Authorization = `Bearer ${this.token}`;
    let res: Response;
    try {
      res = await fetch(this.base + path, {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: ctl.signal,
      });
    } catch (e) {
      throw new ApiError(0, ctl.signal.aborted ? 'timeout' : 'offline', ctl.signal.aborted ? 'The TV took too long to answer' : "Can't reach the TV");
    } finally {
      clearTimeout(timer);
    }
    let data: any = null;
    try { data = await res.json(); } catch { /* empty or non-JSON */ }
    if (!res.ok) {
      // /api/play answers ok:false with candidates; let callers read it.
      if (data && typeof data === 'object' && 'ok' in data) return data as T;
      throw new ApiError(res.status, data?.error ?? `http_${res.status}`, undefined, data);
    }
    return data as T;
  }

  // pairing
  pairRequest(device: string) { return this.req<{ ok: boolean }>('/api/pair/request', { method: 'POST', body: { device }, auth: false }); }
  pairConfirm(pin: string) { return this.req<{ token: string; name: string }>('/api/pair/confirm', { method: 'POST', body: { pin }, auth: false }); }

  // state
  info(timeout?: number) { return this.req<TvInfo>('/api/info', { timeout }); }
  now() { return this.req<NowPlaying>('/api/now', { timeout: 4000 }); }
  keysBootstrap() { return this.req<{ adb_port: number; cmd: string }>('/api/keys/bootstrap'); }
  apps() { return this.req<TvApp[]>('/api/apps'); }

  // control
  key(key: Key, long = false) { return this.req<Ok>('/api/key', { method: 'POST', body: { key, long }, timeout: 3000 }); }
  text(text: string, submit = false) { return this.req<Ok>('/api/text', { method: 'POST', body: { text, submit } }); }
  launch(pkg: string) { return this.req<Ok>('/api/launch', { method: 'POST', body: { pkg } }); }
  open(url: string, pkg?: string) { return this.req<Ok>('/api/open', { method: 'POST', body: { url, pkg } }); }

  // content
  search(q: string) { return this.req<Title[]>(`/api/search?q=${encodeURIComponent(q)}`, { timeout: 10000 }); }
  recs() { return this.req<Title[]>('/api/recs', { timeout: 10000 }); }
  recent() { return this.req<RecentItem[]>('/api/recent'); }
  play(p: Partial<PlayRequest>) {
    const body: PlayRequest = { title: p.title ?? null, pkg: p.pkg ?? null, kind: p.kind ?? null, id: p.id ?? null };
    return this.req<PlayResult>('/api/play', { method: 'POST', body, timeout: 12000 });
  }

  // wallpapers: pictures on the TV for its screensaver, and one as the home's background
  wallpapers() { return this.req<WallpaperList>('/api/wallpapers'); }
  wallpaperThumb(name: string) {
    return {
      uri: `${this.base}/api/wallpapers/thumb?name=${encodeURIComponent(name)}`,
      headers: this.token ? { Authorization: `Bearer ${this.token}` } : undefined,
      cacheKey: `${this.host}:${this.port}/wallpaper/${name}`,
    };
  }
  deleteWallpaper(name: string) { return this.req<Ok>(`/api/wallpapers?name=${encodeURIComponent(name)}`, { method: 'DELETE' }); }
  setBackground(name: string | null) { return this.req<Ok>('/api/wallpapers/background', { method: 'POST', body: { name } }); }

  // lights: WiZ bulbs on the TV's Wi-Fi; every call answers with the whole state
  lights() { return this.req<Lights>('/api/lights', { timeout: 15000 }); }
  setLights(p: LightsPatch) { return this.req<Lights>('/api/lights', { method: 'POST', body: p, timeout: 4000 }); }
  /** Scans the LAN, which takes the TV up to ~4 s. */
  discoverLights() { return this.req<Lights>('/api/lights/discover', { method: 'POST', timeout: 20000 }); }
  setLightsAuto(p: Partial<LightsAuto>) { return this.req<Lights>('/api/lights/auto', { method: 'POST', body: p }); }

  /** Send a picture from this phone (streamed from its file); the TV crops it to 16:9, 1920x1080. */
  async uploadWallpaper(fileUri: string, asBackground = false): Promise<{ ok: boolean; name: string }> {
    const res = await FileSystem.uploadAsync(`${this.base}/api/wallpapers${asBackground ? '?use=background' : ''}`, fileUri, {
      httpMethod: 'POST',
      uploadType: FileSystem.FileSystemUploadType.BINARY_CONTENT,
      headers: { 'Content-Type': 'application/octet-stream', ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}) },
    }).catch(() => { throw new ApiError(0, 'offline', "Can't reach the TV"); });
    let data: any = null;
    try { data = JSON.parse(res.body); } catch { /* not JSON */ }
    if (res.status !== 200) throw new ApiError(res.status, data?.error ?? `http_${res.status}`);
    return data;
  }
}
