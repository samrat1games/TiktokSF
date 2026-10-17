package cat.narezany.margyt;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

/**
 * The liquid-glass look of compose-hig's panels: a translucent body, a soft
 * highlight running from the top left corner, a thin bright rim, and -- where
 * the system can do it (Android 12 and up) -- the screen behind blurred.
 * Anywhere else it is just the translucent card.
 */
final class ImGlass {
    private ImGlass() {}

    static Drawable card(int fill, float[] radii, int rim) {
        int translucent = (fill & 0xFFFFFF) | 0xB8000000;
        GradientDrawable body = new GradientDrawable();
        body.setColor(translucent);
        body.setCornerRadii(radii);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x47FFFFFF, 0x0DFFFFFF, 0x00FFFFFF});
        sheen.setCornerRadii(radii);
        GradientDrawable edge = new GradientDrawable();
        edge.setColor(0);
        edge.setCornerRadii(radii);
        edge.setStroke(rim, 0x59FFFFFF);
        return new LayerDrawable(new Drawable[]{body, sheen, edge});
    }

    static void blurBehind(Window window, int radiusPx) {
        try {
            if (window == null || Build.VERSION.SDK_INT < 31) return;
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.setBlurBehindRadius(radiusPx);
            window.setAttributes(lp);
        } catch (Throwable ignored) {
        }
    }
}
