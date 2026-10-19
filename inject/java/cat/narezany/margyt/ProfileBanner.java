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
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.WeakHashMap;

/**
 * A banner across the top of your own profile: a picture of your choice, behind the name,
 * the numbers and the avatar, fading into the page below them.
 *
 * It is the background of the profile's header block (found by the id that block has in
 * this TikTok), so nothing is added to the page and nothing moves. Only the profile that
 * is a tab of the main screen gets it, which is your own; another person's profile is a
 * screen of its own and is left alone. Every step gives up quietly.
 */
final class ProfileBanner {
    private ProfileBanner() {}

    private static final String FILE = "profile_banner.jpg";
    private static final String VERSION = "profile_banner_version";
    private static final int TAG = 0x50424E52;
    private static final WeakHashMap<Activity, Boolean> WATCHED = new WeakHashMap<Activity, Boolean>();
    private static Bitmap cached;
    private static long cachedVersion = -1;

    static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    static boolean has(Context context) {
        return file(context).exists();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Margy.PREFS, Context.MODE_PRIVATE);
    }

    /** Keep a picture the person chose, scaled down so it is not a burden on a phone's memory. */
    static boolean take(Context context, Uri source) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(source)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 1440) sample *= 2;
            BitmapFactory.Options real = new BitmapFactory.Options();
            real.inSampleSize = sample;
            Bitmap picture;
            try (InputStream in = context.getContentResolver().openInputStream(source)) {
                picture = BitmapFactory.decodeStream(in, null, real);
            }
            if (picture == null) return false;
            try (FileOutputStream out = new FileOutputStream(file(context))) {
                picture.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            picture.recycle();
            prefs(context).edit().putLong(VERSION, System.currentTimeMillis()).apply();
            return true;
        } catch (Throwable error) {
            Diary.note("profile banner: " + error);
            return false;
        }
    }

    static void remove(Context context) {
        try {
            file(context).delete();
            prefs(context).edit().putLong(VERSION, System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static Bitmap picture(Context context) {
        long version = prefs(context).getLong(VERSION, 0L);
        if (cached != null && cachedVersion == version) return cached;
        cached = null;
        File file = file(context);
        if (file.exists()) cached = BitmapFactory.decodeFile(file.getPath());
        cachedVersion = version;
        return cached;
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
            Bitmap picture = picture(activity);
            Object mine = header.getTag(TAG);
            if (picture == null) {
                if (mine instanceof Banner) {
                    header.setBackground(((Banner) mine).before);
                    header.setTag(TAG, null);
                }
                return;
            }
            if (mine instanceof Banner && ((Banner) mine).picture == picture) return;
            Drawable before = mine instanceof Banner ? ((Banner) mine).before : header.getBackground();
            int page;
            try {
                page = Md3.under(header) | 0xFF000000;
            } catch (Throwable ignored) {
                page = 0xFF000000;
            }
            Banner banner = new Banner(picture, before, page);
            header.setTag(TAG, banner);
            header.setBackground(banner);
        } catch (Throwable error) {
            Diary.detail("profile banner: " + error);
        }
    }

    private static final class Banner extends Drawable {
        final Bitmap picture;
        final Drawable before;
        private final int page;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint wash = new Paint();
        private final Rect from = new Rect();
        private final RectF to = new RectF();

        Banner(Bitmap picture, Drawable before, int page) {
            this.picture = picture;
            this.before = before;
            this.page = page;
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
            float scale = Math.max(b.width() / (float) picture.getWidth(), high / picture.getHeight());
            int w = Math.round(b.width() / scale), h = Math.round(high / scale);
            int x = Math.max(0, (picture.getWidth() - w) / 2), y = Math.max(0, (picture.getHeight() - h) / 2);
            from.set(x, y, Math.min(picture.getWidth(), x + w), Math.min(picture.getHeight(), y + h));
            to.set(b.left, b.top, b.right, b.top + high);
            int save = canvas.saveLayer(b.left, b.top, b.right, b.bottom, null);
            canvas.drawBitmap(picture, from, to, paint);
            // calm the picture so the name and numbers on it can be read, then let it fade into the page
            boolean light = (0.299f * ((page >> 16) & 255) + 0.587f * ((page >> 8) & 255) + 0.114f * (page & 255)) / 255f > 0.5f;
            int veil = light ? 0x8CFFFFFF : 0x73000000;
            wash.setShader(new LinearGradient(0f, b.top, 0f, b.top + high,
                    new int[] {veil, veil, page}, new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(b.left, b.top, b.right, b.top + high, wash);
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
