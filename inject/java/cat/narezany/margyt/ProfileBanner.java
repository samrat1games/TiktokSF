package cat.narezany.margyt;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.WeakHashMap;

/**
 * A banner across the top of your own profile: a picture, a GIF or a short video, behind
 * the name, the numbers and the avatar, fading into the page below them.
 *
 * It is the background of the profile's header block (found by the id that block has in
 * this TikTok), so nothing is added to the page and nothing moves. Only the profile that
 * is a tab of the main screen gets it, which is your own; another person's profile is a
 * screen of its own and is left alone. Every step gives up quietly.
 *
 * What is kept is one of three things: a picture, a GIF (the file as it came), or a
 * video taken apart into a few small frames that are played round and round without
 * sound. A video is never played as a video: nothing here asks the phone for a decoder
 * on a page that is already busy.
 */
final class ProfileBanner {
    private ProfileBanner() {}

    static final int NONE = 0, IMAGE = 1, GIF = 2, FRAMES = 3;

    private static final String KIND = "profile_banner_kind";
    private static final String VERSION = "profile_banner_version";
    private static final String FPS = "profile_banner_fps";
    private static final int TAG = 0x50424E52;
    private static final int FRAME_WIDTH = 540, MAX_FRAMES = 72, SECONDS = 6;
    private static final WeakHashMap<Activity, Boolean> WATCHED = new WeakHashMap<Activity, Boolean>();

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Margy.PREFS, Context.MODE_PRIVATE);
    }

    private static File home(Context context) {
        File dir = new File(context.getFilesDir(), "banner");
        dir.mkdirs();
        return dir;
    }

    static int kind(Context context) {
        return prefs(context).getInt(KIND, NONE);
    }

    static boolean has(Context context) {
        return kind(context) != NONE;
    }

    static long version(Context context) {
        return prefs(context).getLong(VERSION, 0L);
    }

    private static void done(Context context, int kind, int fps) {
        prefs(context).edit().putInt(KIND, kind).putInt(FPS, fps).putLong(VERSION, System.currentTimeMillis()).apply();
    }

    private static void clear(Context context) {
        File[] all = home(context).listFiles();
        if (all != null) for (File file : all) {
            if (file.isDirectory()) {
                File[] inside = file.listFiles();
                if (inside != null) for (File one : inside) one.delete();
            }
            file.delete();
        }
    }

    static void remove(Context context) {
        try {
            clear(context);
            done(context, NONE, 0);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ taking one

    /** What a person picked on the phone. */
    static boolean take(Context context, Uri source) {
        File copy = new File(context.getCacheDir(), "banner_in");
        try {
            try (InputStream in = context.getContentResolver().openInputStream(source);
                 OutputStream out = new FileOutputStream(copy)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    total += n;
                    if (total > 120L * 1024 * 1024) return false;
                }
            }
            return takeFile(context, copy);
        } catch (Throwable error) {
            Diary.note("profile banner: " + error);
            return false;
        } finally {
            copy.delete();
        }
    }

    /** A file of any of the three kinds, told apart by what it begins with, not by its name. */
    static boolean takeFile(Context context, File file) {
        try {
            byte[] head = new byte[16];
            try (FileInputStream in = new FileInputStream(file)) {
                if (in.read(head) < 12) return false;
            }
            String start = new String(head, 0, 12, "ISO-8859-1");
            boolean gif = start.startsWith("GIF8");
            boolean webp = start.startsWith("RIFF") && start.endsWith("WEBP");
            boolean video = start.substring(4, 8).equals("ftyp") || (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45;
            if (video) return takeVideo(context, file);
            if (gif || webp) return takeAnimated(context, file);
            return takeImage(context, file);
        } catch (Throwable error) {
            Diary.note("profile banner: " + error);
            return false;
        }
    }

    private static boolean takeImage(Context context, File file) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= 1440) sample *= 2;
        BitmapFactory.Options real = new BitmapFactory.Options();
        real.inSampleSize = sample;
        Bitmap picture = BitmapFactory.decodeFile(file.getPath(), real);
        if (picture == null) return false;
        clear(context);
        try (FileOutputStream out = new FileOutputStream(new File(home(context), "banner.jpg"))) {
            picture.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        picture.recycle();
        done(context, IMAGE, 0);
        return true;
    }

    private static boolean takeAnimated(Context context, File file) throws Exception {
        clear(context);
        try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(new File(home(context), "banner.gif"))) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        done(context, GIF, 0);
        return true;
    }

    private static boolean takeVideo(Context context, File file) throws Exception {
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        File staging = new File(home(context), "frames_new");
        try {
            reader.setDataSource(file.getPath());
            long length = Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int width = Integer.parseInt(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.parseInt(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String turned = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (turned != null && (turned.equals("90") || turned.equals("270"))) {
                int swap = width;
                width = height;
                height = swap;
            }
            if (width <= 0 || height <= 0 || length <= 0) return false;
            long span = Math.min(length, SECONDS * 1000L);
            int count = (int) Math.max(1, Math.min(MAX_FRAMES, span / 100L));
            int fps = (int) Math.max(1, Math.round(count * 1000.0 / span));
            int fw = Math.min(FRAME_WIDTH, width), fh = Math.max(1, Math.round(height * (fw / (float) width)));
            staging.mkdirs();
            File[] old = staging.listFiles();
            if (old != null) for (File f : old) f.delete();
            int got = 0;
            for (int i = 0; i < count; i++) {
                long at = span * i / count;
                Bitmap frame = reader.getScaledFrameAtTime(at * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, fw, fh);
                if (frame == null) continue;
                try (FileOutputStream out = new FileOutputStream(new File(staging, String.format("%03d.jpg", got)))) {
                    frame.compress(Bitmap.CompressFormat.JPEG, 82, out);
                }
                frame.recycle();
                got++;
            }
            if (got == 0) return false;
            File[] before = home(context).listFiles();
            if (before != null) for (File f : before) {
                if (f.equals(staging)) continue;
                if (f.isDirectory()) {
                    File[] in = f.listFiles();
                    if (in != null) for (File one : in) one.delete();
                }
                f.delete();
            }
            File frames = new File(home(context), "frames");
            if (!staging.renameTo(frames)) return false;
            done(context, FRAMES, Math.max(1, Math.round(got * 1000f / span)));
            return true;
        } finally {
            try {
                reader.release();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------- doing it off the main thread

    private interface Job { boolean run() throws Exception; }

    private static void run(Context context, final Job job, final Runnable done) {
        final Context app = context.getApplicationContext();
        Screen.say(Local.of("Баннер обновляется…"));
        Net.away("banner", () -> {
            boolean good = false;
            try {
                good = job.run();
            } catch (Throwable error) {
                Diary.note("profile banner: " + error);
            }
            final boolean ok = good;
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                Screen.say(ok ? Local.of("Баннер установлен") : Local.of("Не получилось сделать баннер"));
                if (done != null) done.run();
            });
        });
    }

    static void fromUri(Context context, final Uri uri, Runnable done) {
        final Context app = context.getApplicationContext();
        run(context, () -> take(app, uri), done);
    }

    static void fromLink(Context context, final String link, Runnable done) {
        final Context app = context.getApplicationContext();
        run(context, () -> {
            File file = BannerLink.fetch(app, link);
            try {
                return takeFile(app, file);
            } finally {
                file.delete();
            }
        }, done);
    }

    /** Addresses TikTok's own app gave for a post's video or photo. */
    static void fromAddresses(Context context, final java.util.List<String> addresses, Runnable done) {
        final Context app = context.getApplicationContext();
        run(context, () -> {
            File file = BannerLink.download(app, addresses, null, true);
            try {
                return takeFile(app, file);
            } finally {
                file.delete();
            }
        }, done);
    }

    // --------------------------------------------------------- showing it

    private static Drawable media(Context context) {
        try {
            File dir = home(context);
            switch (kind(context)) {
                case IMAGE: {
                    Bitmap picture = BitmapFactory.decodeFile(new File(dir, "banner.jpg").getPath());
                    return picture == null ? null : new BitmapDrawable(context.getResources(), picture);
                }
                case GIF: {
                    File file = new File(dir, "banner.gif");
                    if (android.os.Build.VERSION.SDK_INT >= 28) return Animated.open(file);
                    Bitmap first = BitmapFactory.decodeFile(file.getPath());
                    return first == null ? null : new BitmapDrawable(context.getResources(), first);
                }
                case FRAMES: {
                    File[] files = new File(dir, "frames").listFiles();
                    if (files == null || files.length == 0) return null;
                    java.util.Arrays.sort(files);
                    return new Frames(files, Math.max(1, prefs(context).getInt(FPS, 10)));
                }
                default:
                    return null;
            }
        } catch (Throwable error) {
            Diary.note("profile banner: " + error);
            return null;
        }
    }

    /** Kept apart so a phone older than Android 9 never has to know the class exists. */
    private static final class Animated {
        static Drawable open(File file) throws Exception {
            Drawable drawable = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(file),
                    (decoder, info, source) -> {
                        int w = info.getSize().getWidth();
                        if (w > 720) decoder.setTargetSampleSize(Math.max(1, w / 720));
                    });
            if (drawable instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) drawable).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            }
            return drawable;
        }

        static void start(Drawable drawable) {
            if (drawable instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) drawable).start();
        }
    }

    /** Called for every screen; watches its layout and dresses the profile when it is there. */
    static void attach(final Activity activity) {
        try {
            if (WATCHED.containsKey(activity)) return;
            WATCHED.put(activity, Boolean.TRUE);
            final View decor = activity.getWindow().getDecorView();
            final long[] last = {0L};
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                long now = SystemClock.uptimeMillis();
                if (now - last[0] < 350L) return;
                last[0] = now;
                dress(activity, decor);
            });
        } catch (Throwable error) {
            Diary.detail("profile banner: " + error);
        }
    }

    private static void dress(Activity activity, View decor) {
        try {
            if (activity.getClass().getName().contains("UserProfile")) return;
            int id = activity.getResources().getIdentifier("t_j", "id", activity.getPackageName());
            if (id == 0) return;
            View header = decor.findViewById(id);
            if (header == null) return;
            Object mine = header.getTag(TAG);
            if (!has(activity)) {
                if (mine instanceof Banner) {
                    header.setBackground(((Banner) mine).before);
                    header.setTag(TAG, null);
                }
                return;
            }
            long version = version(activity);
            if (mine instanceof Banner && ((Banner) mine).version == version) return;
            Drawable shown = media(activity);
            if (shown == null) return;
            Drawable before = mine instanceof Banner ? ((Banner) mine).before : header.getBackground();
            int page;
            try {
                page = Md3.under(header) | 0xFF000000;
            } catch (Throwable ignored) {
                page = 0xFF000000;
            }
            Banner banner = new Banner(shown, before, page, version);
            header.setTag(TAG, banner);
            header.setBackground(banner);
            banner.begin();
        } catch (Throwable error) {
            Diary.detail("profile banner: " + error);
        }
    }

    /** A few small frames, round and round. */
    private static final class Frames extends Drawable {
        private final File[] files;
        private final int fps;
        private final long since = SystemClock.uptimeMillis();
        private final android.util.LruCache<Integer, Bitmap> cache = new android.util.LruCache<Integer, Bitmap>(10);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Runnable tick = this::invalidateSelf;
        private int wide = FRAME_WIDTH, high = FRAME_WIDTH / 2;
        private boolean waiting;

        Frames(File[] files, int fps) {
            this.files = files;
            this.fps = fps;
            Bitmap first = frame(0);
            if (first != null) {
                wide = first.getWidth();
                high = first.getHeight();
            }
        }

        private Bitmap frame(int index) {
            Bitmap have = cache.get(index);
            if (have == null) {
                have = BitmapFactory.decodeFile(files[index].getPath());
                if (have != null) cache.put(index, have);
            }
            return have;
        }

        @Override public int getIntrinsicWidth() { return wide; }
        @Override public int getIntrinsicHeight() { return high; }

        @Override public void draw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            int index = (int) (((now - since) * fps / 1000L) % files.length);
            Bitmap picture = frame(index);
            if (picture != null) canvas.drawBitmap(picture, null, getBounds(), paint);
            if (!waiting) {
                waiting = true;
                scheduleSelf(() -> {
                    waiting = false;
                    invalidateSelf();
                }, now + 1000L / fps);
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    private static final class Banner extends Drawable implements Drawable.Callback {
        final Drawable media;
        final Drawable before;
        final long version;
        private final int page;
        private final Paint wash = new Paint();
        private final Rect box = new Rect();

        Banner(Drawable media, Drawable before, int page, long version) {
            this.media = media;
            this.before = before;
            this.page = page;
            this.version = version;
            media.setCallback(this);
        }

        void begin() {
            if (android.os.Build.VERSION.SDK_INT >= 28) Animated.start(media);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            if (before != null) {
                before.setBounds(b);
                before.draw(canvas);
            }
            // the picture covers the top of the header and is cropped to fit, never stretched
            float high = b.height() * 0.78f;
            int iw = Math.max(1, media.getIntrinsicWidth()), ih = Math.max(1, media.getIntrinsicHeight());
            float scale = Math.max(b.width() / (float) iw, high / ih);
            int dw = Math.round(iw * scale), dh = Math.round(ih * scale);
            int left = b.left + (b.width() - dw) / 2, top = b.top + Math.round((high - dh) / 2f);
            int save = canvas.save();
            canvas.clipRect(b.left, b.top, b.right, b.top + Math.round(high));
            box.set(left, top, left + dw, top + dh);
            media.setBounds(box);
            media.draw(canvas);
            canvas.restoreToCount(save);
            // calm the picture so the name and numbers on it can be read, then let it fade into the page
            boolean light = (0.299f * ((page >> 16) & 255) + 0.587f * ((page >> 8) & 255) + 0.114f * (page & 255)) / 255f > 0.5f;
            int veil = light ? 0x8CFFFFFF : 0x73000000;
            wash.setShader(new LinearGradient(0f, b.top, 0f, b.top + high,
                    new int[] {veil, veil, page}, new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(b.left, b.top, b.right, b.top + high, wash);
        }

        @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }

        @Override public void setAlpha(int alpha) { media.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { media.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
