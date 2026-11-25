package top.zainzh.rearscreen;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

/** Temporary IME focus handoff on the exact virtual display; never owns touch or opens the keyboard. */
public final class RearImeFocusActivity extends Activity {
    static final String EXTRA_DISPLAY_ID = "rearImeDisplayId";
    static final String EXTRA_DISPLAY_NAME = "rearImeDisplayName";
    static final String EXTRA_LOCKED_HOME_SEED = "rearImeLockedHomeSeed";
    private boolean lockedHomeSeed;
    private boolean receivedFocus;
    private final Runnable closeTask = this::finishAndRemoveTask;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Display display = getDisplay();
        int id = getIntent().getIntExtra(EXTRA_DISPLAY_ID, Display.INVALID_DISPLAY);
        String name = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        lockedHomeSeed = getIntent().getBooleanExtra(EXTRA_LOCKED_HOME_SEED, false);
        if (display == null || keyguard == null || !RearImeStartupFocus.canHostHelper(id, name,
                display.getDisplayId(), display.getName(), keyguard.isKeyguardLocked(), lockedHomeSeed)) {
            finishAndRemoveTask();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        getWindow().setDimAmount(0f);
        getWindow().setWindowAnimations(0);
        if (lockedHomeSeed) {
            // Publish an actual empty InputConnection on this virtual display. It must not
            // request an IME, accept touches, turn either screen on or request keyguard dismissal.
            EditText editor = new EditText(this);
            editor.setShowSoftInputOnFocus(false);
            editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            editor.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO);
            editor.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            editor.setBackground(null);
            setContentView(editor);
            editor.requestFocus();
        } else {
            setContentView(new View(this));
        }
        getWindow().getDecorView().postDelayed(closeTask, lockedHomeSeed ? 1200 : 600);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (isFinishing()) return;
        if (lockedHomeSeed && receivedFocus && !focused) {
            finishAndRemoveTask();
            return;
        }
        if (!focused) return;
        receivedFocus = true;
        CompatibilityDiagnostics.record("virtual_ime_focus_helper", "display=" + getDisplay().getDisplayId()
                + " state=" + (lockedHomeSeed ? "seed_editor_focused" : "focused"));
        if (!lockedHomeSeed) {
            View decor = getWindow().getDecorView();
            decor.removeCallbacks(closeTask);
            decor.postDelayed(closeTask, 100);
        }
    }

    @Override protected void onDestroy() {
        getWindow().getDecorView().removeCallbacks(closeTask);
        super.onDestroy();
    }
}
