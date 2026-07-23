package com.familylocation.client;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;

import com.familylocation.net.Material3Tokens;

final class ClientUiStyle {
    interface DarkModeProvider {
        boolean isDarkMode();
    }

    private final Context context;
    private final DarkModeProvider darkModeProvider;

    ClientUiStyle(Context context) {
        this(context, null);
    }

    ClientUiStyle(Context context, DarkModeProvider darkModeProvider) {
        this.context = context;
        this.darkModeProvider = darkModeProvider;
    }

    int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    boolean isDarkMode() {
        if (darkModeProvider != null) {
            return darkModeProvider.isDarkMode();
        }
        return systemDarkMode();
    }

    private boolean systemDarkMode() {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    int colorSurface() {
        return Material3Tokens.surface(isDarkMode());
    }

    int colorSurfaceContainer() {
        return Material3Tokens.surfaceContainer(isDarkMode());
    }

    int colorText() {
        return Material3Tokens.onSurface(isDarkMode());
    }

    int colorMuted() {
        return Material3Tokens.onSurfaceVariant(isDarkMode());
    }

    int colorAccent() {
        return colorPrimary();
    }

    int colorAccentMuted() {
        return isDarkMode() ? Color.rgb(46, 61, 57) : Color.rgb(217, 226, 223);
    }

    int colorPrimary() {
        return Material3Tokens.primary(isDarkMode());
    }

    int colorOnPrimary() {
        return Material3Tokens.onPrimary(isDarkMode());
    }

    int colorOutline() {
        return Material3Tokens.outline(isDarkMode());
    }

    int colorRipple() {
        return Material3Tokens.ripple(isDarkMode());
    }

    void stylePrimaryButton(Button button) {
        stylePrimaryButton(button, false);
    }

    void stylePrimaryButton(Button button, boolean dense) {
        button.setTextColor(colorOnPrimary());
        button.setAllCaps(false);
        button.setTextSize(dense ? 14f : 14f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(dense ? 46 : 42));
        button.setPadding(dp(dense ? 14 : 14), 0, dp(dense ? 14 : 14), 0);
        styleButtonShape(button, colorPrimary(), dp(dense ? 23 : 10), 0, colorPrimary());
    }

    void styleSecondaryButton(Button button) {
        styleSecondaryButton(button, false);
    }

    void styleSecondaryButton(Button button, boolean dense) {
        button.setTextColor(colorPrimary());
        button.setAllCaps(false);
        button.setTextSize(dense ? 14f : 14f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(dense ? 46 : 42));
        button.setPadding(dp(dense ? 14 : 14), 0, dp(dense ? 14 : 14), 0);
        styleButtonShape(button, colorSurfaceContainer(), dp(dense ? 23 : 10), dp(1), colorOutline());
    }

    void styleInput(EditText view) {
        styleInput(view, false);
    }

    void styleInput(EditText view, boolean dense) {
        view.setTextColor(colorText());
        view.setHintTextColor(colorMuted());
        view.setTextSize(dense ? 14f : 14f);
        view.setPadding(dp(dense ? 14 : 14), 0, dp(dense ? 14 : 14), 0);
        view.setMinHeight(dp(dense ? 48 : 44));
        view.setBackground(strokedDrawable(colorSurfaceContainer(), dp(dense ? 14 : 10), colorOutline(), dp(1)));
    }

    void styleCheckBox(CheckBox box, boolean dense) {
        box.setTextColor(colorText());
        box.setTextSize(dense ? 13.5f : 13f);
        box.setButtonTintList(new ColorStateList(
            new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked},
            },
            new int[]{colorPrimary(), colorMuted()}
        ));
    }

    int screenPadding(boolean dense) {
        return dp(dense ? 16 : 22);
    }

    float screenTitleSize(boolean dense) {
        return dense ? 18f : 22f;
    }

    float sectionTitleSize(boolean dense) {
        return dense ? 16f : 13.5f;
    }

    float bodyTextSize(boolean dense) {
        return dense ? 12.5f : 12.5f;
    }

    float compactBodyTextSize(boolean dense) {
        return dense ? 11.5f : 12f;
    }

    int infoPanelHorizontalPadding(boolean dense) {
        return dp(dense ? 12 : 16);
    }

    int infoPanelVerticalPadding(boolean dense) {
        return dp(dense ? 10 : 14);
    }

    int compactPanelHorizontalPadding(boolean dense) {
        return dp(dense ? 10 : 14);
    }

    int compactPanelVerticalPadding(boolean dense) {
        return dp(dense ? 8 : 12);
    }

    int bottomNavOuterHorizontalPadding(boolean dense) {
        return dp(dense ? 14 : 20);
    }

    int bottomNavOuterBottomPadding(boolean dense) {
        return dp(dense ? 8 : 10);
    }

    int navItemMinHeight(boolean dense) {
        return dp(dense ? 46 : 52);
    }

    int navItemVerticalPadding(boolean dense) {
        return dp(dense ? 4 : 6);
    }

    float navIconSize(boolean dense, boolean active) {
        if (dense) {
            return active ? 18f : 16f;
        }
        return active ? 20f : 18f;
    }

    float navLabelSize(boolean dense) {
        return dense ? 9f : 10f;
    }

    int mapPreviewHeight(boolean dense) {
        return dp(dense ? 220 : 300);
    }

    void styleHomePrimaryButton(Button button) {
        styleHomePrimaryButton(button, false);
    }

    void styleHomePrimaryButton(Button button, boolean dense) {
        button.setTextColor(colorOnPrimary());
        button.setAllCaps(false);
        button.setTextSize(dense ? 12.5f : 16f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(dense ? 38 : 52));
        button.setPadding(dp(dense ? 10 : 18), 0, dp(dense ? 10 : 18), 0);
        styleButtonShape(button, colorPrimary(), dp(dense ? 8 : 26), 0, colorPrimary());
    }

    void styleHomeSecondaryButton(Button button) {
        styleHomeSecondaryButton(button, false);
    }

    void styleHomeSecondaryButton(Button button, boolean dense) {
        button.setTextColor(colorPrimary());
        button.setAllCaps(false);
        button.setTextSize(dense ? 12.5f : 16f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(dense ? 38 : 52));
        button.setPadding(dp(dense ? 10 : 18), 0, dp(dense ? 10 : 18), 0);
        styleButtonShape(button, colorSurfaceContainer(), dp(dense ? 8 : 26), dp(1), colorOutline());
    }

    void styleHistorySecondaryButton(Button button, boolean dense) {
        button.setTextColor(colorPrimary());
        button.setAllCaps(false);
        button.setTextSize(dense ? 12f : 14f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(dense ? 36 : 44));
        button.setPadding(dp(dense ? 8 : 14), 0, dp(dense ? 8 : 14), 0);
        styleButtonShape(button, colorSurfaceContainer(), dp(dense ? 8 : 22), dp(1), colorOutline());
    }

    private void styleButtonShape(Button button, int fillColor, int cornerRadius, int strokeWidth, int strokeColor) {
        if (strokeWidth > 0) {
            button.setBackground(strokedDrawable(fillColor, cornerRadius, strokeColor, strokeWidth));
        } else {
            button.setBackground(rippleDrawable(fillColor, cornerRadius));
        }
    }

    GradientDrawable navActiveBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Material3Tokens.primaryContainer(isDarkMode()));
        drawable.setCornerRadius(dp(20));
        return drawable;
    }

    GradientDrawable bottomNavBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(colorSurfaceContainer());
        drawable.setCornerRadius(dp(28));
        drawable.setStroke(dp(1), colorOutline());
        return drawable;
    }

    GradientDrawable transparentButtonBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(dp(16));
        return drawable;
    }

    GradientDrawable cardBackground() {
        return roundedDrawable(colorSurfaceContainer(), dp(8));
    }

    GradientDrawable panelBackground() {
        return strokedDrawable(colorSurfaceContainer(), dp(8), colorOutline(), dp(1));
    }

    GradientDrawable pillBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(isDarkMode() ? Color.rgb(25, 55, 49) : Color.rgb(228, 241, 238));
        drawable.setCornerRadius(dp(999));
        return drawable;
    }

    GradientDrawable roundedDrawable(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    GradientDrawable strokedDrawable(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = roundedDrawable(color, radius);
        drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    RippleDrawable rippleDrawable(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(colorRipple()), roundedDrawable(color, radius), null);
    }
}
