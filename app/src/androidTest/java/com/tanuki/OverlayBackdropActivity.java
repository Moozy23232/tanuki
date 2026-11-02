package com.tanuki;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Separate instrumentation-APK UID/process; Java avoids its intentionally deduplicated Kotlin runtime. */
public final class OverlayBackdropActivity extends Activity {
    private int count;
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(235, 242, 233));
        TextView heading = new TextView(this);
        heading.setText("另一个应用 · 公开测试页面\n与浏览器不同的 Android 包 / UID");
        heading.setTextSize(19); heading.setTextColor(Color.rgb(40, 67, 40)); heading.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams headingParams = new FrameLayout.LayoutParams(-1, dp(100), Gravity.TOP);
        headingParams.topMargin = dp(24); root.addView(heading, headingParams);
        final TextView counter = new TextView(this);
        counter.setText("底层应用点击：0"); counter.setTextSize(18); counter.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams counterParams = new FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM);
        counterParams.bottomMargin = dp(112); root.addView(counter, counterParams);
        Button button = new Button(this); button.setText("验证窗口外的触摸"); button.setAllCaps(false);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { count++; counter.setText("底层应用点击：" + count); }
        });
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM);
        buttonParams.leftMargin = dp(24); buttonParams.rightMargin = dp(24); buttonParams.bottomMargin = dp(48);
        root.addView(button, buttonParams); setContentView(root);
    }
    @Override public void onStop() { super.onStop(); if (!isChangingConfigurations()) finish(); }
}
