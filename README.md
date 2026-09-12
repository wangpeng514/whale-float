# 小鲸鱼余额悬浮窗（WhaleFloat）

一个**最小**的安卓悬浮窗 App：屏幕上的鲸鱼（作者的 DSniang1.png）+ 一个余额气泡。
余额由 App **自己直连** `https://api.deepseek.com/user/balance` 查询，**不依赖 DSH**，DSH 关着也能用。

## 和 DSH 自带的悬浮窗的关系（重要）

DSH App 自己也有一个悬浮鲸鱼图标（只显示引擎状态，不显示余额）。**两个同时开会叠在一起**，所以只能留一个：

- **方案 A（推荐）**：装这个 App 显示余额鲸鱼，把 DSH 那只收起来。
  - 直接在系统设置 → 应用 → DeepSeek Harness → 「显示在其他应用上层」关掉，最彻底；
  - 或者让 AI 调 `android_overlay hide`（我实测过：返回成功，但状态里 `running` 仍为 true，所以不保证真的隐藏）。
- **方案 B**：只用 DSH 那只（不显示余额）。

## 构建

需要 JDK 17 + Android SDK（`ANDROID_HOME` 指向 SDK）。

```sh
cd whale-float
gradle wrapper --gradle-version 8.7   # 只需跑一次，生成 ./gradlew
./gradlew assembleDebug               # 产物 app/build/outputs/apk/debug/app-debug.apk
```

用 Android Studio 更省事：`File → Open → 选 whale-float 目录`，直接点 Run。

装到手机：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 或者把 apk 传到手机上点安装
```

发布版要签名：在 `app/build.gradle` 加 `signingConfigs`，或用 Android Studio 的
`Build → Generate Signed Bundle / APK`。

## 怎么用

1. 打开 App（叫「小鲸鱼余额」），填入 **DeepSeek API Key**（`sk-` 开头那个）
2. 可选：改刷新间隔（默认 60 秒，范围 10–3600）
3. 点「**显示鲸鱼**」——首次会带你去系统设置开「显示在其他应用上层」，开完回来再点一次
4. Android 13+ 会弹通知权限，**必须允许**：前台服务靠这个通知保活
5. 之后可以把 App 从后台划掉，鲸鱼继续浮在屏幕上

操作：

- 拖鲸鱼**左上角的 ✥ 把手**挪位置，松手自动吸附到最近的左右边（位置会记住）
- 点**鲸鱼**或**气泡** → 立刻刷新余额
- **长按气泡** → 复制余额文字
- 回 App 点「隐藏鲸鱼」→ 关掉悬浮窗

## 代码结构

| 文件 | 作用 |
|---|---|
| `MainActivity.java` | 配置界面：API Key、刷新间隔、开/关；处理悬浮窗与通知权限 |
| `FloatingWhaleService.java` | 悬浮窗本体：窗口创建、拖动吸附、余额轮询、前台保活 |
| `res/layout/overlay_whale.xml` | 悬浮窗内容：把手 + 气泡 + 鲸鱼图 |
| `res/drawable/whale.png` | 作者的小鲸鱼（cut-out，来自 DSniang1.png，255988 字节） |

## 设计取舍（为什么这么写）

- **自己查余额**：不依赖 3080 端口的 DSH 服务，也不用 `DEEPSEEK_PLATFORM_TOKEN`；漏掉的是
  「今日已用」和峰谷判定——要那些就得去读 DSH 插件的 `/dsh-whale/balance.json`。
- **前台服务 + specialUse**：`targetSdk 34` 下必须有前台服务类型，`specialUse` 是用户主动开启的
  常驻挂件的正规模板（已在 manifest 里声明 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）。
- **网络抖动沿用上次值**：拿到过一次余额后，后续失败不闪错误，继续显示旧数字；只有从没成功过
  才显示 `读余额失败：…`。
- **进程被杀能自己回来**：`START_STICKY` + `onStartCommand(intent == null)` 分支，系统回收后会用
  null intent 重启服务，此时只要用户上次是「开着」的状态就重建窗口。
- **没做开机自启**：那需要额外的 `RECEIVE_BOOT_COMPLETED` 权限和广播接收器；如果你想开机自动出现，
  告诉我，加一个 `BootReceiver` 就行（大约 20 行）。

## 已知的粗糙处

- 鲸鱼图用的是固定 `150dp`，没有做多分辨率切图（原图 610×610，任何密度下都够清晰）。
- 气泡是纯白圆角 + 深蓝描边，跟网页挂件的 SVG 气泡只是「风格相近」，不是像素级复刻。
- 吸附只做左右，没做上下四边（网页挂件是四边四分之一吸附）。需要的话可以补。
