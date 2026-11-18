package top.zainzh.rearscreen;

oneway interface IRearWallpaperOutputReleased {
    void onReleased(String sessionId, int hostTask);
}
