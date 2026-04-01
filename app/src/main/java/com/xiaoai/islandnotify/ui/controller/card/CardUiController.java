package com.xiaoai.islandnotify;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.switchmaterial.SwitchMaterial;

final class CardUiController {

    private CardUiController() {}

    interface BoolAction {
        void run(boolean value);
    }

    static void bindSwitch(SwitchMaterial toggle, boolean initialChecked, BoolAction onChanged) {
        if (toggle == null) return;
        toggle.setChecked(initialChecked);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (onChanged != null) onChanged.run(checked);
        });
    }

    static void bindSwitchContent(SwitchMaterial toggle, View content, boolean initialChecked, BoolAction onChanged) {
        if (toggle == null) return;
        toggle.setChecked(initialChecked);
        setVisible(content, initialChecked);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            setVisible(content, checked);
            if (onChanged != null) onChanged.run(checked);
        });
    }

    static void showHint(TextView hintView, String message) {
        if (hintView == null) return;
        hintView.setText(message == null ? "" : message);
        hintView.setVisibility(View.VISIBLE);
    }

    static void applyDirtyButtonTint(Context context, MaterialButton button, boolean dirty) {
        if (context == null || button == null) return;
        int color = dirty
                ? Color.parseColor("#FF9800")
                : MaterialColors.getColor(
                        context,
                        com.google.android.material.R.attr.colorPrimary,
                        Color.parseColor("#6200EE"));
        button.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    private static void setVisible(View view, boolean visible) {
        if (view == null) return;
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
