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
        updateTile(ScreenRecordService.isRunning());
    }
    
    @Override
    public void onClick() {
        super.onClick();
        
        unlockAndRun(() -> {
            Intent intent = new Intent(this, ScreenRecordService.class);
            // Stopping an existing recording must not depend on opening a new overlay.
            if (ScreenRecordService.isRunning()) {
                stopService(intent);
                updateTile(false);
                return;
            }
            if (TilePermissionPrompt.needsConnection(this)) return;
            if (!Settings.canDrawOverlays(this)) {
                TilePermissionPrompt.openOverlaySettings(this);
                return;
            }
            startForegroundService(intent);
            updateTile(true);
        });
    }

    private void updateTile(boolean running) {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }
}






