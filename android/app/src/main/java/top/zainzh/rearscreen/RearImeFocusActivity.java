package top.zainzh.rearscreen;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

/** Transparent focus handoff on the exact virtual display; never owns touch or shows an IME. */
public final class RearImeFocusActivity extends Activity {
    static final String EXTRA_DISPLAY_ID = "rearImeDisplayId";
    static final String EXTRA_DISPLAY_NAME = "rearImeDisplayName";
    private final Runnable closeTask = this::finishAndRemoveTask;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Display display = getDisplay();
        int id = getIntent().getIntExtra(EXTRA_DISPLAY_ID, Display.INVALID_DISPLAY);
        String name = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (display == null || id <= Display.DEFAULT_DISPLAY || display.getDisplayId() != id
                || name == null || !name.startsWith(RearVirtualIme.DISPLAY_NAME_PREFIX)
                || !name.equals(display.getName()) || keyguard == null || keyguard.isKeyguardLocked()) {
            finishAndRemoveTask();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        getWindow().setDimAmount(0f);
        getWindow().setWindowAnimations(0);
        setContentView(new View(this));
        getWindow().getDecorView().postDelayed(closeTask, 600);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused || isFinishing()) return;
        CompatibilityDiagnostics.record("virtual_ime_focus_helper", "display=" + getDisplay().getDisplayId() + " state=focused");
        View decor = getWindow().getDecorView();
        decor.removeCallbacks(closeTask);
        decor.postDelayed(closeTask, 100);
    }

    @Override protected void onDestroy() {
        getWindow().getDecorView().removeCallbacks(closeTask);
        super.onDestroy();
    }
}
