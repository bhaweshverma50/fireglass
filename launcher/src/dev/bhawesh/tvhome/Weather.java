package dev.bhawesh.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * "31°  Haze · Gurugram"-style line for the top bar. City from the TV's public IP (ip-api.com, no key),
 * weather from Open-Meteo (no key). Refreshed at most every 30 minutes; blank when offline.
 */
final class Weather {
    private Weather() { }

    private static final long TTL_MS = 30 * 60 * 1000L;

    /** Blocking; call off the main thread. */
    static String line(Context c) {
        SharedPreferences p = c.getSharedPreferences("weather", Context.MODE_PRIVATE);
        // Lines saved before the city was shown are refreshed once.
        if (p.contains("city") && System.currentTimeMillis() - p.getLong("at", 0) < TTL_MS) return p.getString("line", "");
        try {
            String lat = p.getString("lat", null), lon = p.getString("lon", null);
            if (lat == null || !p.contains("city") || System.currentTimeMillis() - p.getLong("geoAt", 0) > 24 * 3600 * 1000L) {
                JSONObject g = new JSONObject(Net.get("http://ip-api.com/json/?fields=status,city,lat,lon"));
                if (!"success".equals(g.optString("status"))) return p.getString("line", "");
                lat = String.valueOf(g.getDouble("lat"));
                lon = String.valueOf(g.getDouble("lon"));
                p.edit().putString("lat", lat).putString("lon", lon).putString("city", g.optString("city"))
                        .putLong("geoAt", System.currentTimeMillis()).apply();
            }
            JSONObject cur = new JSONObject(Net.get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon + "&current=temperature_2m,weather_code&timezone=auto"))
                    .getJSONObject("current");
            // Where the weather is for: the city the TV's internet connection appears to be in.
            String city = p.getString("city", "");
            String line = Math.round(cur.getDouble("temperature_2m")) + "°  " + describe(cur.getInt("weather_code"))
                    + (city.isEmpty() ? "" : " · " + city);
            p.edit().putString("line", line).putLong("at", System.currentTimeMillis()).apply();
            return line;
        } catch (Exception e) {
            return p.getString("line", "");
        }
    }

    /** WMO weather code in a few plain words. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code <= 48) return "Fog";
        if (code <= 57) return "Drizzle";
        if (code <= 67) return "Rain";
        if (code <= 77) return "Snow";
        if (code <= 82) return "Showers";
        return "Thunderstorms";
    }
}
