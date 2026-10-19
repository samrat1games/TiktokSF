package cat.narezany.margyt;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A banner from the address of a post, or from the addresses TikTok's own app already has for one.
 *
 * Given a link (a short one from the share button, or the long one from a browser) it follows the link to
 * the post's page, reads the post's own description out of that page -- the same one a browser reads --
 * and takes the video's address or the first photo's from it. Nothing is asked of anyone but TikTok.
 * Must not be called on the main thread.
 */
final class BannerLink {
    private BannerLink() {}

    private static final String AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final long LIMIT = 60L * 1024 * 1024;

    /** The first web address in whatever was pasted, or null. */
    static String find(String pasted) {
        if (pasted == null) return null;
        Matcher m = Pattern.compile("https?://[^\\s]+").matcher(pasted);
        return m.find() ? m.group().replaceAll("[)\\]\\.,]+$", "") : null;
    }

    private static boolean ours(String host) {
        if (host == null) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        return host.equals("tiktok.com") || host.endsWith(".tiktok.com") || host.endsWith(".tiktokv.com")
                || host.endsWith(".tiktokcdn.com") || host.endsWith(".tiktokcdn-us.com") || host.endsWith(".muscdn.com")
                || host.endsWith(".tiktokcdn-eu.com") || host.endsWith(".byteoversea.com") || host.endsWith(".ibytedtos.com");
    }

    /** The picture or the video of a post, in a file of ours; throws when there is none to be had. */
    static File fetch(Context context, String link) throws Exception {
        String address = find(link);
        if (address == null) throw new java.io.IOException("no address");
        Map<String, String> cookies = new LinkedHashMap<String, String>();
        String page = page(address, cookies);
        String json = between(page, "id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"", "</script>");
        if (json == null) throw new java.io.IOException("the post's page had no description");
        json = json.substring(json.indexOf('>') + 1);
        JSONObject scope = new JSONObject(json).getJSONObject("__DEFAULT_SCOPE__");
        JSONObject item = scope.getJSONObject("webapp.video-detail").getJSONObject("itemInfo").getJSONObject("itemStruct");

        List<String> candidates = new ArrayList<String>();
        if (item.has("imagePost")) {
            JSONArray images = item.getJSONObject("imagePost").getJSONArray("images");
            JSONArray list = images.getJSONObject(0).getJSONObject("imageURL").getJSONArray("urlList");
            for (int i = 0; i < list.length(); i++) candidates.add(list.getString(i));
        } else {
            JSONObject video = item.getJSONObject("video");
            JSONArray rates = video.optJSONArray("bitrateInfo");
            if (rates != null) for (int i = 0; i < rates.length(); i++) {
                JSONArray urls = rates.getJSONObject(i).getJSONObject("PlayAddr").optJSONArray("UrlList");
                if (urls != null) for (int j = 0; j < urls.length(); j++) candidates.add(urls.getString(j));
            }
            if (video.optString("playAddr").length() > 0) candidates.add(0, video.getString("playAddr"));
            if (video.optString("downloadAddr").length() > 0) candidates.add(video.getString("downloadAddr"));
        }
        return download(context, candidates, cookies, false);
    }

    /** Files from addresses TikTok's app itself gave: the first that comes. */
    static File download(Context context, List<String> addresses, Map<String, String> cookies, boolean trusted) throws Exception {
        Throwable last = null;
        File file = new File(context.getCacheDir(), "banner_dl");
        for (String raw : addresses) {
            try {
                String address = raw.replace("\\u002F", "/");
                if (!address.startsWith("https://") || (!trusted && !ours(new URL(address).getHost()))) continue;
                HttpURLConnection con = (HttpURLConnection) new URL(address).openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                con.setRequestProperty("User-Agent", AGENT);
                con.setRequestProperty("Referer", "https://www.tiktok.com/");
                if (cookies != null && !cookies.isEmpty()) con.setRequestProperty("Cookie", join(cookies));
                if (con.getResponseCode() / 100 != 2) throw new java.io.IOException("status " + con.getResponseCode());
                long total = 0;
                try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        total += n;
                        if (total > LIMIT) throw new java.io.IOException("too big");
                    }
                }
                if (total > 1024) return file;
            } catch (Throwable error) {
                last = error;
            }
        }
        throw new java.io.IOException(last == null ? "nothing to download" : String.valueOf(last));
    }

    private static String join(Map<String, String> cookies) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }

    private static String page(String address, Map<String, String> cookies) throws Exception {
        for (int hops = 0; hops < 6; hops++) {
            URL url = new URL(address);
            if (!url.getProtocol().equals("https") || !ours(url.getHost())) throw new java.io.IOException("not a TikTok address");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);
            con.setRequestProperty("User-Agent", AGENT);
            con.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            con.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            if (!cookies.isEmpty()) con.setRequestProperty("Cookie", join(cookies));
            int code = con.getResponseCode();
            List<String> set = con.getHeaderFields().get("Set-Cookie");
            if (set != null) for (String one : set) {
                int eq = one.indexOf('='), end = one.indexOf(';');
                if (eq > 0) cookies.put(one.substring(0, eq).trim(), one.substring(eq + 1, end > eq ? end : one.length()));
            }
            if (code / 100 == 3) {
                String to = con.getHeaderField("Location");
                if (to == null) throw new java.io.IOException("redirect with nowhere to go");
                address = new URL(url, to).toString();
                continue;
            }
            if (code / 100 != 2) throw new java.io.IOException("status " + code);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = con.getInputStream()) {
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    body.write(buffer, 0, n);
                    if (body.size() > 6 * 1024 * 1024) break;
                }
            }
            return body.toString("UTF-8");
        }
        throw new java.io.IOException("too many redirects");
    }

    private static String between(String text, String from, String to) {
        int a = text.indexOf(from);
        if (a < 0) return null;
        int b = text.indexOf(to, a);
        return b < 0 ? null : text.substring(a + from.length() - 0, b);
    }
}
