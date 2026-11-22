package kr.onradio.player;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class RadioCatalog {
    record Station(String id, String name, String group, String country, String description,
                   String color, String resolver, String channel, String url, String homepage, String timeZone) {
        static Station read(Context context, JSONObject s) {
            String id = s.optString("id");
            boolean custom = id.startsWith("custom-");
            boolean korean = context.getString(R.string.language_code).equals("ko");
            String name = localized(s, "name", korean);
            String group = custom ? context.getString(R.string.custom_station) : localized(s, "group", korean);
            String description = custom ? context.getString(R.string.custom_description) : localized(s, "description", korean);
            return new Station(id, name, group, s.optString("country"), description, s.optString("color", "#E4B96C"),
                s.optString("resolver", "direct"), s.optString("channel"), s.optString("url"),
                s.optString("homepage"), s.optString("time_zone"));
        }
    }

    static final String[] COUNTRY_CODES = {"", "KR", "US", "GB", "CA", "JP"};
    private static final int[] COUNTRY_NAMES = {
        R.string.all_countries, R.string.korea, R.string.united_states, R.string.united_kingdom,
        R.string.canada, R.string.japan
    };

    private static String localized(JSONObject station, String key, boolean korean) {
        return korean ? station.optString(key) : station.optString(key + "_en", station.optString(key));
    }

    static String[] countryNames(Context context) {
        String[] names = new String[COUNTRY_NAMES.length];
        for (int i = 0; i < names.length; i++) names[i] = context.getString(COUNTRY_NAMES[i]);
        return names;
    }

    static String countryName(Context context, String code) {
        for (int i = 1; i < COUNTRY_CODES.length; i++) {
            if (COUNTRY_CODES[i].equals(code)) return context.getString(COUNTRY_NAMES[i]);
        }
        return context.getString(R.string.custom_station);
    }

    static String countryKeywords(String code) {
        return switch (code) {
            case "KR" -> "South Korea 한국";
            case "US" -> "United States USA 미국";
            case "GB" -> "United Kingdom UK 영국";
            case "CA" -> "Canada 캐나다";
            case "JP" -> "Japan 일본";
            default -> "Custom 직접 추가";
        };
    }

    static List<Station> load(Context context) {
        try (InputStream stream = context.getAssets().open("catalog.json")) {
            return readStations(context, new JSONObject(readText(stream)).getJSONArray("stations"));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read bundled station catalog", error);
        }
    }

    static List<Station> readStations(Context context, JSONArray items) throws Exception {
        List<Station> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) result.add(Station.read(context, items.getJSONObject(i)));
        return result;
    }

    static String resolve(Station station) throws Exception {
        return switch (station.resolver()) {
            case "direct" -> webUrl(station.url());
            case "mbc" -> webUrl(get("https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel="
                + Uri.encode(station.channel())));
            case "sbs" -> {
                String path = switch (station.channel()) {
                    case "power" -> "powerpc/powerfm";
                    case "love" -> "lovepc/lovefm";
                    default -> throw new IllegalArgumentException("Unknown SBS service");
                };
                yield webUrl(get("https://apis.sbs.co.kr/play-api/1.0/livestream/"
                    + path + "?protocol=hls&ssl=Y"));
            }
            case "kbs" -> {
                var response = new JSONObject(get(
                    "https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/" + Uri.encode(station.channel())));
                JSONArray entries = response.getJSONArray("channel_item");
                String url = "";
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    if ("radio".equals(entry.optString("media_type")) && !entry.optString("service_url").isEmpty()) {
                        url = entry.getString("service_url");
                        break;
                    }
                }
                yield webUrl(url);
            }
            default -> throw new IllegalArgumentException("Unknown stream format");
        };
    }

    static String webUrl(String input) {
        String value = input.trim();
        Uri parsed = Uri.parse(value);
        String scheme = parsed.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)
                || parsed.getHost() == null || parsed.getHost().trim().isEmpty() || parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("An HTTP or HTTPS stream URL is required.");
        }
        return value;
    }

    static HttpURLConnection connection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(12_000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        return connection;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = connection(url);
        try (InputStream stream = connection.getInputStream()) {
            return readText(stream);
        } finally {
            connection.disconnect();
        }
    }

    static String readText(InputStream input) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (result.size() + count > 2_000_000) throw new IllegalArgumentException("Response too large");
            result.write(buffer, 0, count);
        }
        return new String(result.toByteArray(), StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");
    }
}
