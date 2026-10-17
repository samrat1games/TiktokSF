package cat.narezany.margyt;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

/** The capsule button of compose-hig: a flat fill, fully round, with a press highlight. */
final class ImButton {
    private ImButton() {}

    static Drawable capsule(int fill, int press, int radiusPx) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radiusPx);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFF000000);
        mask.setCornerRadius(radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(press), shape, mask);
    }
}
