package cat.narezany.margyt;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The banner, on TikTok's own "Edit profile" screen: a card of its own at the top of the list, under
 * the avatar, made to look like the cards beside it. Tapping it offers a picture, GIF or video from
 * the phone, a link to a post, or taking the banner off. Nothing on the screen is moved or changed;
 * a screen that is not what it was (another release) simply gets no card.
 */
final class EditProfileRow {
    private EditProfileRow() {}

    private static final int TAG = 0x45505257;

    static void dress(final Activity activity, View decor) {
        try {
            if (!activity.getClass().getName().endsWith("ProfileEditActivity")) return;
            int id = activity.getResources().getIdentifier("t_s", "id", activity.getPackageName());
            if (id == 0) return;
            View found = decor.findViewById(id);
            if (!(found instanceof LinearLayout)) return;
            LinearLayout column = (LinearLayout) found;
            if (column.getChildCount() == 0) return;
            for (int i = 0; i < column.getChildCount(); i++) if (column.getChildAt(i).getTag(TAG) != null) {
                refresh(column.getChildAt(i), activity);
                return;
            }
            View first = column.getChildAt(0);
            if (first.getHeight() <= 0) return;

            final float density = activity.getResources().getDisplayMetrics().density;
            TextView sample = firstText(first);
            int ink = sample != null ? sample.getCurrentTextColor() : 0xFF161823;
            float size = sample != null ? sample.getTextSize() : 16f * density;
            boolean dark = (0.299f * Color.red(ink) + 0.587f * Color.green(ink) + 0.114f * Color.blue(ink)) / 255f > 0.5f;

            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            Drawable theirs = background(first);
            if (theirs != null && theirs.getConstantState() != null) {
                card.setBackground(theirs.getConstantState().newDrawable().mutate());
            } else {
                GradientDrawable plate = new GradientDrawable();
                plate.setColor(dark ? 0xFF1C1C1E : 0xFFF1EFF5);
                plate.setCornerRadius(24f * density);
                card.setBackground(plate);
            }

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int rowHigh = Math.max(Math.round(44f * density), Math.round(first.getHeight() / 4f));
            int side = Math.round(16f * density);
            row.setPadding(side, 0, side, 0);

            TextView label = new TextView(activity);
            label.setText(Local.of("Баннер"));
            label.setTextColor(ink);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
            if (sample != null) label.setTypeface(sample.getTypeface());
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView value = new TextView(activity);
            value.setTag("value");
            value.setTextColor((ink & 0xFFFFFF) | 0x99000000);
            value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
            value.setPadding(0, 0, Math.round(8f * density), 0);
            row.addView(value);

            Chevron arrow = new Chevron(activity, (ink & 0xFFFFFF) | 0x99000000, false, false);
            arrow.setRotation(-90f);
            row.addView(arrow, new LinearLayout.LayoutParams(Math.round(20f * density), Math.round(20f * density)));

            card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHigh));
            card.setOnClickListener(v -> choose(activity));
            card.setTag(TAG, Boolean.TRUE);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ViewGroup.LayoutParams theirsParams = first.getLayoutParams();
            if (theirsParams instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) theirsParams;
                params.leftMargin = m.leftMargin;
                params.rightMargin = m.rightMargin;
            }
            params.bottomMargin = Math.round(12f * density);
            refresh(card, activity);
            column.addView(card, 0, params);
        } catch (Throwable error) {
            Diary.detail("edit profile banner: " + error);
        }
    }

    private static void refresh(View card, Activity activity) {
        View v = card.findViewWithTag("value");
        if (v instanceof TextView) {
            ((TextView) v).setText(ProfileBanner.has(activity) ? Local.of("Установлен") : Local.of("Нет"));
        }
    }

    private static Drawable background(View view) {
        for (int depth = 0; view != null && depth < 4; depth++) {
            if (view.getBackground() != null) return view.getBackground();
            if (view instanceof ViewGroup && ((ViewGroup) view).getChildCount() > 0) view = ((ViewGroup) view).getChildAt(0);
            else break;
        }
        return null;
    }

    private static TextView firstText(View view) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE && ((TextView) view).length() > 0) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = firstText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void choose(final Activity activity) {
        Panel panel = Panel.with(activity, Skin.remembered(activity), Local.of("Баннер профиля"));
        panel.item("image", Local.of("Фото, GIF или видео с телефона"), null, () -> {
            Intent intent = new Intent();
            intent.setClassName(activity.getPackageName(), "cat.narezany.margyt.SettingsActivity");
            intent.putExtra("pick_banner", true);
            activity.startActivity(intent);
        });
        panel.item("link", Local.of("По ссылке на видео TikTok"), Local.of("Ссылка из кнопки «Поделиться»"), () -> link(activity));
        if (ProfileBanner.has(activity)) {
            panel.danger("block", Local.of("Убрать баннер"), null, () -> {
                ProfileBanner.remove(activity);
                Screen.say(Local.of("Баннер убран"));
            });
        }
        panel.quiet(Text.CLOSE, null).show();
    }

    private static void link(final Activity activity) {
        Panel panel = Panel.with(activity, Skin.remembered(activity), Local.of("Баннер по ссылке"));
        panel.text(Local.of("Откройте видео в TikTok, нажмите «Поделиться → Скопировать ссылку» и вставьте её сюда."));
        final android.widget.EditText field = panel.field("https://vm.tiktok.com/…");
        try {
            android.content.ClipboardManager clips = (android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clips != null && clips.hasPrimaryClip() && clips.getPrimaryClip().getItemCount() > 0) {
                CharSequence pasted = clips.getPrimaryClip().getItemAt(0).coerceToText(activity);
                if (pasted != null && pasted.toString().contains("tiktok.com")) field.setText(pasted);
            }
        } catch (Throwable ignored) {
        }
        panel.primary(Local.of("Сделать баннером"), () -> {
            String text = field.getText().toString();
            if (BannerLink.find(text) == null) {
                Screen.say(Local.of("Это не ссылка"));
                return;
            }
            ProfileBanner.fromLink(activity, text, null);
        });
        panel.quiet(Text.CLOSE, null).show();
    }
}
