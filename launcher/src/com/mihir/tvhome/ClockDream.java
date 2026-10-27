package com.mihir.tvhome;

import android.service.dreams.DreamService;

/** System screensaver (used where the OS honours it); the launcher also shows SaverView itself after idling. */
public class ClockDream extends DreamService {
    private SaverView v;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        v = new SaverView(this);
        setContentView(v);
    }
    @Override public void onDreamingStarted() { super.onDreamingStarted(); v.start(); }
    @Override public void onDreamingStopped() { v.stop(); super.onDreamingStopped(); }
}
