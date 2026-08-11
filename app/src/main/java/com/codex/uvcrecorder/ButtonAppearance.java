package com.codex.uvcrecorder;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

final class ButtonAppearance {
    private ButtonAppearance() {
    }

    static void apply(View root, int opacityPercent) {
        if (root == null) return;
        if (root instanceof AudioLevelMeterView) {
            ((AudioLevelMeterView) root).setPanelOpacity(opacityPercent);
        }
        if (root instanceof Button || root.getId() == R.id.info_panel) {
            Drawable background = root.getBackground();
            if (background != null) {
                background.mutate().setAlpha(Math.round(255f
                        * Math.max(20, Math.min(100, opacityPercent)) / 100f));
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                apply(group.getChildAt(i), opacityPercent);
            }
        }
    }
}
