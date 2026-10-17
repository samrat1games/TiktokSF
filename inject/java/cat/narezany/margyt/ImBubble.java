package cat.narezany.margyt;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * A message bubble shaped like the one in iMessage: a round body with a small
 * tail at the bottom corner on the sender's side.
 *
 * The tail is the one from the bubble artwork (148 x 139, tail swooping out of
 * the lower right of a body whose corners are 59 units round), scaled so that
 * its corner matches the body's. Outgoing bubbles carry it on the right,
 * incoming ones are the mirror image. The tail hangs below the body, so the
 * body stops a tail's height short of the bottom of the view.
 *
 * Applied to the message row from {@link MessageTimes}; every step gives up
 * quietly, so a row that is not what it was means a plain bubble, never a crash.
 */
final class ImBubble extends Drawable {

    static final String KEY = "imessage_bubbles";
    private static final int TAG = 0x494D4255;
    private static final int OUT = 0xFF0B84FF;

    /** Art units: the tail's right edge, and how far below the body it reaches. */
    private static final float ART_CORNER = 59f, ART_BODY_H = 118f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final boolean incoming;
    private final float density;
    private final Drawable previous;

    ImBubble(int colour, boolean incoming, float density, Drawable previous) {
        this.incoming = incoming;
        this.density = density;
        this.previous = previous;
        paint.setColor(colour);
    }

    @Override public void draw(Canvas canvas) { canvas.drawPath(path, paint); }

    @Override protected void onBoundsChange(Rect b) {
        path.reset();
        float radius = Math.min(18f * density, Math.min(b.width(), b.height()) / 2f);
        float s = radius / ART_CORNER;                 // art units -> px
        float hang = (139f - ART_BODY_H) * s;          // the tail below the body
        float bottom = Math.max(b.top + radius * 2, b.bottom - hang);
        path.addRoundRect(new RectF(b.left, b.top, b.right, bottom), radius, radius, Path.Direction.CW);

        // the tail, drawn in art coordinates with the body's lower right at (148, 118)
        Path tail = new Path();
        tail.moveTo(128.5f, 99.5f);
        tail.cubicTo(122f, 107.5f, 118.21f, 112.505f, 118.21f, 119.607f);
        tail.cubicTo(118.21f, 125.617f, 120.421f, 130.535f, 123.737f, 134.359f);
        tail.cubicTo(124.842f, 135.998f, 124.842f, 137.091f, 123.737f, 137.637f);
        tail.cubicTo(122.079f, 138.73f, 118.763f, 137.637f, 115.447f, 136.545f);
        tail.cubicTo(109.368f, 133.813f, 102.737f, 129.988f, 96.1052f, 125.617f);
        tail.cubicTo(91.1316f, 122.339f, 86.1579f, 118.515f, 81.7368f, 114.69f);
        tail.cubicTo(92.2368f, 116.329f, 101.632f, 114.69f, 110.474f, 110.865f);
        tail.cubicTo(117.105f, 108.134f, 124.632f, 104.964f, 128.5f, 99.5f);
        tail.close();
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postScale(s, s);
        m.postTranslate(b.right - 148f * s, bottom - ART_BODY_H * s);
        if (incoming) m.postScale(-1f, 1f, (b.left + b.right) / 2f, 0f);
        tail.transform(m);
        path.addPath(tail);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    // -- applying it to a message row -------------------------------------

    static boolean enabled() {
        try {
            android.content.Context context = Margy.context();
            if (context == null) return true;
            return context.getSharedPreferences(Margy.PREFS, android.content.Context.MODE_PRIVATE)
                    .getBoolean(KEY, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** The message itself: the biggest thing in the row, a few levels down. */
    private static View content(ViewGroup root) {
        View node = root, best = null;
        for (int depth = 0; depth < 4 && node instanceof ViewGroup; depth++) {
            ViewGroup group = (ViewGroup) node;
            View pick = null;
            int area = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                int size = child.getWidth() * child.getHeight();
                if (size > area) { area = size; pick = child; }
            }
            if (pick == null) break;
            best = pick;
            node = pick;
        }
        return best;
    }

    private static boolean hasText(View view) {
        if (view instanceof TextView) return ((TextView) view).length() > 0;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i))) return true;
        return false;
    }

    private static void ink(View view, int colour) {
        if (view instanceof TextView) ((TextView) view).setTextColor(colour);
        else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) ink(group.getChildAt(i), colour);
        }
    }

    /** Give the message in this row the bubble, or put back what it had. */
    static void attach(ViewGroup root, boolean incoming) {
        try {
            View target = content(root);
            if (target == null || target.getWidth() == 0) return;
            Object old = target.getTag(TAG);
            if (!enabled() || !hasText(target)) { detach(target); return; }
            if (old instanceof ImBubble && ((ImBubble) old).incoming == incoming) return;
            Drawable before = old instanceof ImBubble ? ((ImBubble) old).previous : target.getBackground();
            boolean dark = Themes.isDark();
            int colour = incoming ? (dark ? 0xFF262628 : 0xFFE9E9EB) : OUT;
            ImBubble bubble = new ImBubble(colour, incoming, target.getResources().getDisplayMetrics().density, before);
            target.setTag(TAG, bubble);
            target.setBackground(bubble);
            ink(target, incoming ? (dark ? 0xFFFFFFFF : 0xFF000000) : 0xFFFFFFFF);
        } catch (Throwable error) {
            Diary.note("imessage bubble: " + error);
        }
    }

    static void detach(View target) {
        if (target == null) return;
        Object old = target.getTag(TAG);
        if (old instanceof ImBubble) {
            target.setBackground(((ImBubble) old).previous);
            target.setTag(TAG, null);
        }
    }

    static void detach(ViewGroup root) { detach(content(root)); }
}
