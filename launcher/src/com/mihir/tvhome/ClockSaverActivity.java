package com.mihir.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

/** Shown by the launcher after it has sat idle; any remote button returns to Home. */
public class ClockSaverActivity extends Activity {
    private SaverView v;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        v = new SaverView(this);
        setContentView(v);
    }
    @Override protected void onResume() { super.onResume(); v.start(); }
    @Override protected void onPause() { v.stop(); super.onPause(); finish(); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) { finish(); return true; }
}
