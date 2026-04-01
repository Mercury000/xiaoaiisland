package com.xiaoai.islandnotify;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

final class StatusCardController {

    private StatusCardController() {}

    static void render(AppCompatActivity activity, boolean active, String frameworkDesc) {
        MaterialCardView card = activity.findViewById(R.id.card_status);
        ImageView icon = activity.findViewById(R.id.iv_status);
        TextView title = activity.findViewById(R.id.tv_status_title);
        TextView desc = activity.findViewById(R.id.tv_status_desc);
        if (card == null || icon == null || title == null || desc == null) return;

        if (active) {
            int bg = MaterialColors.getColor(activity,
                    com.google.android.material.R.attr.colorPrimaryContainer, Color.LTGRAY);
            int onColor = MaterialColors.getColor(activity,
                    com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE);
            card.setCardBackgroundColor(bg);
            icon.setImageResource(R.drawable.ic_module_active);
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(onColor));
            title.setText("模块已激活");
            title.setTextColor(onColor);
            desc.setText((frameworkDesc == null || frameworkDesc.isEmpty())
                    ? "LSPosed Service 已连接" : frameworkDesc);
            desc.setTextColor(onColor);
            return;
        }

        int bg = MaterialColors.getColor(activity,
                com.google.android.material.R.attr.colorErrorContainer, Color.LTGRAY);
        int onColor = MaterialColors.getColor(activity,
                com.google.android.material.R.attr.colorOnErrorContainer, Color.BLACK);
        card.setCardBackgroundColor(bg);
        icon.setImageResource(R.drawable.ic_module_inactive);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(onColor));
        title.setText("模块未激活");
        title.setTextColor(onColor);
        desc.setText("LSPosed Service 未连接，请检查模块启用与框架状态");
        desc.setTextColor(onColor);
    }
}
