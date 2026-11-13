package com.mihir.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * "Screen dark": a black screen that keeps the soundscape playing. The TV stays on, so the sound
 * and Home Assistant's sleep timer carry on; any remote button brings Home back.
 */
public class DarkActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        View v = new View(this);
        v.setBackgroundColor(Color.BLACK);
        setContentView(v);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0f;   // dims a panel that has its own backlight; HDMI simply shows black
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onPause() { super.onPause(); finish(); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
