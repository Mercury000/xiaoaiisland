package com.xiaoai.islandnotify;

import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;

final class TimeoutCardController {

    private static final int[] ISLAND_PHASE_BUTTON_IDS = {
            R.id.btn_island_phase_pre, R.id.btn_island_phase_active, R.id.btn_island_phase_post
    };
    private static final int[] NOTIF_PHASE_BUTTON_IDS = {
            R.id.btn_notif_phase_pre, R.id.btn_notif_phase_active, R.id.btn_notif_phase_post
    };

    private TimeoutCardController() {}

    static void bind(AppCompatActivity activity, SharedPreferences prefs, Runnable onDirtyChanged) {
        TimeoutConfig timeoutCfg = TimeoutConfig.read(PrefsAccess.resolve(prefs));

        final int[] islandVals = timeoutCfg.islandVals.clone();
        final String[] islandUnits = timeoutCfg.islandUnits.clone();
        final int[] curIslandPhase = {0};

        final int[] notifVals = timeoutCfg.notifVals.clone();
        final String[] notifUnits = timeoutCfg.notifUnits.clone();
        final int[] curNotifPhase = {timeoutCfg.notifTriggerStage};
        final boolean[] notifGlobalDefault = {timeoutCfg.notifGlobalDefault};

        TextInputLayout tilIsland = activity.findViewById(R.id.til_island_to);
        EditText etIsland = activity.findViewById(R.id.et_island_to);
        MaterialButtonToggleGroup toggleIslandUnit = activity.findViewById(R.id.toggle_island_unit);
        SwitchMaterial swIslandDefault = activity.findViewById(R.id.sw_island_to_default);
        MaterialButtonToggleGroup toggleIslandPhase = activity.findViewById(R.id.toggle_island_phase);

        MaterialButtonToggleGroup toggleNotifPhase = activity.findViewById(R.id.toggle_notif_phase);
        TextInputLayout tilNotif = activity.findViewById(R.id.til_notif_to);
        EditText etNotif = activity.findViewById(R.id.et_notif_to);
        MaterialButtonToggleGroup toggleNotifUnit = activity.findViewById(R.id.toggle_notif_unit);
        SwitchMaterial swNotifDefault = activity.findViewById(R.id.sw_notif_to_default);

        TextView tvHint = activity.findViewById(R.id.tv_timeout_hint);
        View btnSave = activity.findViewById(R.id.btn_save_timeout);
        if (tilIsland == null || etIsland == null || toggleIslandUnit == null
                || swIslandDefault == null || toggleIslandPhase == null
                || toggleNotifPhase == null || tilNotif == null || etNotif == null
                || toggleNotifUnit == null || swNotifDefault == null
                || tvHint == null || btnSave == null) {
            return;
        }

        final boolean[] updatingIsland = {false};
        final boolean[] updatingNotif = {false};

        swIslandDefault.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingIsland[0]) return;
            setTimeoutRowEnabled(tilIsland, toggleIslandUnit, !checked);
            if (checked) {
                etIsland.setText("");
                islandVals[curIslandPhase[0]] = ConfigDefaults.TIMEOUT_VALUE;
            }
            notifyDirty(onDirtyChanged);
        });

        swNotifDefault.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingNotif[0]) return;
            notifGlobalDefault[0] = checked;
            if (checked) {
                for (int i = 0; i < ConfigDefaults.STAGE_PHASES.length; i++) {
                    notifVals[i] = ConfigDefaults.TIMEOUT_VALUE;
                }
                etNotif.setText("");
            }
            boolean enabled = !checked;
            setTimeoutRowEnabled(tilNotif, toggleNotifUnit, enabled);
            toggleNotifPhase.setEnabled(enabled);
            toggleNotifPhase.setAlpha(enabled ? 1f : 0.4f);
            notifyDirty(onDirtyChanged);
        });

        final Runnable loadIslandUi = () -> {
            int idx = curIslandPhase[0];
            boolean defaults = islandVals[idx] < 0;
            updatingIsland[0] = true;
            swIslandDefault.setChecked(defaults);
            updatingIsland[0] = false;
            etIsland.setText(defaults ? "" : String.valueOf(islandVals[idx]));
            toggleIslandUnit.check("s".equals(islandUnits[idx]) ? R.id.btn_island_s : R.id.btn_island_m);
            setTimeoutRowEnabled(tilIsland, toggleIslandUnit, !defaults);
        };

        final Runnable saveIslandUi = () -> {
            int idx = curIslandPhase[0];
            if (swIslandDefault.isChecked()) {
                islandVals[idx] = ConfigDefaults.TIMEOUT_VALUE;
            } else {
                String text = etIsland.getText() != null ? etIsland.getText().toString().trim() : "";
                try {
                    islandVals[idx] = text.isEmpty() ? ConfigDefaults.TIMEOUT_VALUE : Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    islandVals[idx] = ConfigDefaults.TIMEOUT_VALUE;
                }
            }
            islandUnits[idx] = toggleIslandUnit.getCheckedButtonId() == R.id.btn_island_s ? "s" : "m";
        };

        toggleIslandPhase.check(buttonIdForStage(ISLAND_PHASE_BUTTON_IDS, curIslandPhase[0]));
        loadIslandUi.run();

        toggleIslandPhase.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            saveIslandUi.run();
            curIslandPhase[0] = stageIndexFromButtonId(checkedId, ISLAND_PHASE_BUTTON_IDS);
            loadIslandUi.run();
            notifyDirty(onDirtyChanged);
        });

        final Runnable loadNotifUi = () -> {
            int idx = curNotifPhase[0];
            updatingNotif[0] = true;
            swNotifDefault.setChecked(notifGlobalDefault[0]);
            updatingNotif[0] = false;
            etNotif.setText(notifVals[idx] > 0 ? String.valueOf(notifVals[idx]) : "");
            toggleNotifUnit.check("s".equals(notifUnits[idx]) ? R.id.btn_notif_s : R.id.btn_notif_m);
            boolean enabled = !notifGlobalDefault[0];
            setTimeoutRowEnabled(tilNotif, toggleNotifUnit, enabled);
            toggleNotifPhase.setEnabled(enabled);
            toggleNotifPhase.setAlpha(enabled ? 1f : 0.4f);
        };

        final Runnable saveNotifUi = () -> {
            int idx = curNotifPhase[0];
            if (swNotifDefault.isChecked()) {
                notifVals[idx] = ConfigDefaults.TIMEOUT_VALUE;
            } else {
                String text = etNotif.getText() != null ? etNotif.getText().toString().trim() : "";
                try {
                    notifVals[idx] = text.isEmpty() ? ConfigDefaults.TIMEOUT_VALUE : Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    notifVals[idx] = ConfigDefaults.TIMEOUT_VALUE;
                }
            }
            notifUnits[idx] = toggleNotifUnit.getCheckedButtonId() == R.id.btn_notif_s ? "s" : "m";
        };

        toggleNotifPhase.check(buttonIdForStage(NOTIF_PHASE_BUTTON_IDS, curNotifPhase[0]));
        loadNotifUi.run();

        toggleNotifPhase.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            saveNotifUi.run();
            curNotifPhase[0] = stageIndexFromButtonId(checkedId, NOTIF_PHASE_BUTTON_IDS);
            loadNotifUi.run();
            notifyDirty(onDirtyChanged);
        });

        btnSave.setOnClickListener(v -> {
            saveNotifUi.run();
            saveIslandUi.run();

            TimeoutConfig saveCfg = TimeoutConfig.read(PrefsAccess.resolve(prefs));
            for (int i = 0; i < ConfigDefaults.STAGE_PHASES.length; i++) {
                saveCfg.islandVals[i] = islandVals[i];
                saveCfg.islandUnits[i] = islandUnits[i];
                saveCfg.notifVals[i] = notifVals[i];
                saveCfg.notifUnits[i] = notifUnits[i];
            }
            saveCfg.notifTriggerStage = curNotifPhase[0];
            saveCfg.notifGlobalDefault = notifGlobalDefault[0];
            SharedPreferences.Editor ed = PrefsAccess.edit(prefs);
            saveCfg.write(ed);
            ed.apply();

            CardUiController.showHint(
                    tvHint, "\u5df2\u4fdd\u5b58\uff0c\u4e0b\u6b21\u901a\u77e5\u751f\u6548");
            notifyDirty(onDirtyChanged);
        });

        etIsland.addTextChangedListener(simpleWatcher(onDirtyChanged));
        etNotif.addTextChangedListener(simpleWatcher(onDirtyChanged));
        notifyDirty(onDirtyChanged);
    }

    static void setTimeoutRowEnabled(
            TextInputLayout til, MaterialButtonToggleGroup unitToggle, boolean enabled) {
        til.setEnabled(enabled);
        til.setAlpha(enabled ? 1f : 0.4f);
        unitToggle.setEnabled(enabled);
        unitToggle.setAlpha(enabled ? 1f : 0.4f);
    }

    static int stageIndexFromButtonId(int checkedId, int[] stageButtons) {
        if (stageButtons == null || stageButtons.length == 0) return ConfigDefaults.STAGE_PRE;
        for (int i = 0; i < stageButtons.length; i++) {
            if (stageButtons[i] == checkedId) return i;
        }
        return ConfigDefaults.STAGE_PRE;
    }

    static int buttonIdForStage(int[] stageButtons, int stageIndex) {
        if (stageButtons == null || stageButtons.length == 0) return View.NO_ID;
        return stageButtons[ConfigDefaults.normalizeStageIndex(stageIndex)];
    }

    private static TextWatcher simpleWatcher(Runnable onDirtyChanged) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                notifyDirty(onDirtyChanged);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        };
    }

    private static void notifyDirty(Runnable onDirtyChanged) {
        if (onDirtyChanged != null) onDirtyChanged.run();
    }
}
