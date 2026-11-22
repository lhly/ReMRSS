package top.zainzh.rearscreen

import android.os.Bundle

/** Lays out the genuine editor on an emulator, without supplying a privileged backend. */
class FixtureWallpaperEditorActivity : AiRearWallpaperEditorActivity() {
    override fun hasEditorDevice() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: Bundle().apply {
            putBoolean("choosing_work", intent.getBooleanExtra("show_work_state", false))
        }
        super.onCreate(state)
    }
}
