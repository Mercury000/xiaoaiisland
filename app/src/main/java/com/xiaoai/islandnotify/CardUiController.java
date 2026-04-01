package com.xiaoai.islandnotify;

import android.view.View;
import android.widget.TextView;

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

    private static void setVisible(View view, boolean visible) {
        if (view == null) return;
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
