# 不装 Android Studio，用 GitHub 免费编译出 APK

## 准备好

- 一个 GitHub 账号（没有就注册，免费）
- 手机上装 **GitHub 官方 App**（应用商店搜 GitHub），或者用浏览器打开 github.com

## 步骤

1. **新建仓库**：GitHub → 右上角 `+` → New repository
   - Repository name 填 `whale-float`
   - 选 **Public**（私有仓库也能用 Actions，但公开更省事）
   - 勾上 `Add a README file`
   - 点 Create repository

2. **把工程文件传上去**：进入刚建好的仓库 → `Add file` → `Upload files`
   - 把 `whale-float/` 里的**所有文件和文件夹**拖进去（包括隐藏目录 `.github`）
   - 提交信息随便写，点 `Commit changes`
   - ⚠️ 关键：`.github/workflows/build.yml` 必须传上去，而且路径不能变，自动化就靠它

3. **等它编译**：仓库页面 → `Actions` 标签
   - 会看到一条 `Build APK` 正在跑（黄点转圈），大约 3–5 分钟
   - 变成绿勾就成功了（红叉说明失败，把日志发我）

4. **下载 APK**：点进那条成功的记录 → 页面底部 `Artifacts` → 点 `whale-float-debug-apk` 下载
   - 下载下来是个 **zip**，解压出来里面是 `app-debug.apk`

5. **安装到手机**：点开那个 apk 安装（首次需要允许"安装未知来源应用"）
   - 装好后按 App 里的引导走：填 API Key → 点「显示鲸鱼」

## 装好之后

- 打开 App「小鲸鱼余额」，填 `sk-` 开头的 API Key，点「显示鲸鱼」
- 首次会带你去开「显示在其他应用上层」权限，开完回来再点一次
- Android 13+ 弹通知权限要**允许**（前台服务靠它保活）
- **重要**：装好后，把 DSH App 自己那个悬浮鲸鱼关掉，不然两只鲸鱼会叠在一起
  （系统设置 → 应用 → DeepSeek Harness → 关掉「显示在其他应用上层」）

## 如果你有电脑（更快）

装了 Android Studio 的话：`File → Open` 选 `whale-float` 目录 → 连上手机 → 点绿色三角 Run。
命令行的话：`cd whale-float && gradle wrapper && ./gradlew assembleDebug`，
产物在 `app/build/outputs/apk/debug/app-debug.apk`。
