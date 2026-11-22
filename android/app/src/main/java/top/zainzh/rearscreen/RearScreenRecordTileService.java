/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 *
 * Chief Tester: 汐木泽
 *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package top.zainzh.rearscreen;

import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings Tile - 背屏录屏
 * 点击后显示/隐藏录屏悬浮窗
 */
public class RearScreenRecordTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }
    
    @Override
    public void onClick() {
        super.onClick();
        
        unlockAndRun(() -> {
            Intent intent = new Intent(this, ScreenRecordService.class);
            // An existing overlay owns the save and close sequence.
            if (ScreenRecordService.closeFromTile()) return;
            if (TilePermissionPrompt.needsConnection(this)) return;
            if (!Settings.canDrawOverlays(this)) {
                TilePermissionPrompt.openOverlaySettings(this);
                return;
            }
            startForegroundService(intent);
        });
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile != null) {
            RecordingPhase phase = ScreenRecordService.currentPhase();
            tile.setState(phase != null ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setSubtitle(phase == null ? null : getString(ScreenRecordService.phaseLabel(phase)));
            tile.updateTile();
        }
    }
}






