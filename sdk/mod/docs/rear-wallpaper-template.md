# 为背屏启动器提供壁纸模板

背屏启动器可以从已安装的签名 ZIP Mod 载入背景和六格布局。模板是数据，使用它不执行 Mod 脚本、不把本机应用列表交给 Mod，也不申请系统文件写入权限。

在现有 Mod 项目中放入一张 PNG 背景。只使用下列路径之一，图片尺寸沿用签名 Mod 的资源限制（宽、高各 1–1024 像素）：

| 已签名的载荷路径 | 布局 |
| --- | --- |
| `assets/rear-wallpaper/six-apps.png` | 6 个应用 |
| `assets/rear-wallpaper/apps-and-list.png` | 5 个应用和「全部应用」 |

例如，用常规 CLI 将已有背景加入一个无脚本项目：

```sh
./bin/remrss create midnight-grid --template blank --author "你的作者名"
mkdir -p midnight-grid/assets/rear-wallpaper
cp background.png midnight-grid/assets/rear-wallpaper/six-apps.png
./bin/remrss pack midnight-grid --key author-private.pem --out midnight-grid.zip
./bin/remrss check midnight-grid.zip
```

在 ReMRSS 的 **模块 → ZIP Mods** 安装这个 ZIP，然后在 Mod 卡片点「配置背屏主题」。静态六宫格模板不需要启动 Mod 的运行时。模板先载入编辑草稿，不会预设包名、Intent、应用绑定或授权；用户仍需选择应用和作品，并确认备份及替换。
