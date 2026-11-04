# 答题锁 TvGuard

电视端的答题防沉迷应用。**不修改电视系统、不锁内容**，只在屏幕点亮时计时，到点覆盖一层全屏答题界面，答对才撤。

给家长管自己孩子看电视用的。开源出来是因为这类工具本来就该透明：它会拿到设备管理员甚至设备所有者权限，代码摆在这里，谁都能看清楚它到底做了什么、没做什么。

```
版本 3.8    最低 Android 5.0 (API 21)    零第三方依赖    协议 GPL-3.0
```

> **先读这一段再动手。**
> 「设备所有者」是安卓最高级别的管理权限。一旦授予，卸载可能被系统拒绝，部分机型只能恢复出厂设置才能彻底摘除。
> 只在**你自己家、你自己孩子的设备**上做这一步。公司设备、租来的设备、别人的设备，不要装。
> 完整的风险和撤销步骤见下面「最终权限（设备所有者）」一节。

## 能装在哪些设备上

程序只用安卓标准接口，没有任何一家厂商的专有依赖。判断依据不是口头承诺，是这三条：

| 检查项 | 结果 |
|---|---|
| 第三方库依赖 | 无。`dependencies {}` 是空的，只用系统 API |
| 厂商判断代码 | 无。源码里没有一处 `Build.BRAND` 影响功能，只有诊断页按品牌显示不同提示文案 |
| 硬件特性要求 | 触摸屏、Leanback、横屏三项全部声明为非必需，手机、平板、电视、盒子都不会被过滤 |
| 最低系统 | Android 5.0。所有高版本 API 都有 `SDK_INT` 判断，Lint 的 `NewApi` 检查为 0 |

实际验证过的环境：

| 设备 | 系统 | 结果 |
|---|---|---|
| 华为智慧屏 S Pro 65（KANT-360S） | HarmonyOS 4.2（安卓兼容层） | 全部功能跑通，已拿到设备所有者 |
| 其余品牌 | 未逐一实机验证 | 机制相同，预期可用 |

华为那台是实测的，其他品牌我没有机器，不能替你打包票。软件里带了一个**兼容性自检**页（设置页 → 计时诊断），换设备先跑一遍，它会把每项系统能力放开没放开逐条列出来，还会实测遥控器按键能不能被接管。跑完把结果发 issue，比猜有用。

**能力会随权限降级，功能不会消失：**

| 拿到什么权限 | 能管到什么程度 |
|---|---|
| 啥也没有（装完就能用） | 只在自己界面里有效，孩子按 HOME 就出去了 |
| 悬浮窗权限 | 能盖在视频上弹题，孩子按 HOME 仍能跑 |
| 无障碍服务 | 能读前台应用、能接管按键，防绕过明显变强 |
| 设备所有者 | 答题期间 HOME、返回、最近任务全部失效；切任何应用都会弹题；卸载被拦 |

最后那一档才是真正管用的，前面几档是路上的一站，不是终点。

## 安装

1. 下载 Releases 里的 APK，拷到 U 盘插电视，用电视自带的文件管理器装。
   没有文件管理器就先装一个，或者用「发送到电视」这类局域网工具推过去。
2. 首次打开会让你设家长密码（4 到 8 位，拒收 1111、1234 这类弱密码），然后选年级。
3. **忘了密码没有找回入口**，这是故意的。出路只有卸载重装。

装完只是能用。要真正管住，还得做下面「最终权限」那一步。

给家长看的图文版在 [`docs/使用说明.html`](docs/使用说明.html)，用浏览器打开即可，
比这份 README 好懂，带截图位置和常见问题对照表。这份 README 偏技术，给想改代码的人看。

著作权归 **AI漫剧花大叔** 所有，按 GPL-3.0 授权，详见 `LICENSE`。


## 为什么不用电视自带的功能

电视厂商把所有带答题的管控都放在「儿童模式」里，而儿童模式必然配内容分级过滤，大孩子要看的东西全被拦掉。标准模式虽然有「使用时间管理」，但只会弹「时间到了休息一下」，没有弹题这个动作。华为官网还自认标准模式的时间管控按遥控器「输入源」键可以跳过。

所以只能让第三方应用接管。

## 功能

| 能力 | 实现 |
|---|---|
| 按年级出题 | 一到六年级上下册，语数英 10937 道加科学 300 道，按年级懒加载 |
| 答题解锁 | 连续答对 N 题才放行，答错清零；4 选 1 连对 3 题通过率 1.6% |
| 时长管控 | 每 N 分钟弹题，每日总时长上限，连错锁定 |
| 学习报告 | 周报月报：时长、答题量、正确率、各科表现、最常错的题、建议 |
| 报告交付 | 电视上直接看 / 手机浏览器看（局域网网页）/ 导出 HTML |
| 多分辨率适配 | 任意电视换算成 960×540 逻辑画布，界面大小三档可调 |
| 家长密码 | 出厂无默认密码，首次启动必须自设；4~8 位，拒重号与顺子；输错罚等 |
| 兼容性自检 | 换电视先跑，逐项列系统能力是否放开；内含遥控器浮窗按键实测 |
| 双形态答题 | 有悬浮窗权限就盖在视频上；没有就自动跳全屏答题页，功能不打折 |
| 退出机制 | 设置页红色入口，二次验密码；三档：停止 / 关闭并解除防卸载 / 卸载 |
| 防绕过 | 返回键拦截、HOME 键焦点夺回、防卸载、设备所有者、开机自启 |

## 首次启动顺序与密码策略

```
没设过密码  → 强制设家长密码（4~8 位，两次确认，拒重号 / 顺子）
有密码没年级 → 年级选择页
都有了      → 密码门 → 设置页
```

- **出厂不带默认密码**。`Prefs.hasPin()` 为 false 就必须先自己设一个，不存在「1234」这种默认值。
- **弱密码拦截**：`1111`、`1234`、`4321`、`12345` 这类纯重复、顺增、顺减一律拒绝。
- **罚等**：`FREE_TRIES = 3` 次内只提示；第 4 次起每次错等 30 秒，9 次 1 分钟，14 次 2 分钟，19 次以上 5 分钟封顶。
  封顶不设太高，是为了避免孩子乱按把家长一起锁在外面。错误次数持久化在本地，重启、断电都不清零，输对一次才清。
  4 位数全试完累计要等 34 天。
- **首次引导跳过最后一道密码门**用进程内一次性令牌 `MainActivity.skipGateOnce`，
  不用 Intent extra：主界面是 exported 的，外部 App 塞个参数就能伪造。

## 退出与关闭

装在别人电视上的东西，必须留一条自己能走出去的路，否则等于装了个关不掉的流氓软件。
设置页底部红色按钮进入 `ExitActivity`，`MODE_CONFIRM_EXIT` 再验一次密码。
能进设置页不代表能拆防护：遥控器乱按也可能钻进设置页，多这一道就拦住了。

| 选项 | 动作 | 数据 |
|---|---|---|
| 今天先停掉 | `setEnabled(false)` + `stopService` + 关局域网服务 | 全留 |
| 关掉并解除防卸载 | 上面 + `OwnerGuard.releaseAll()` | 全留 |
| 卸载并清除全部数据 | 上面 + `ACTION_DELETE` | 全清 |

`releaseAll()` 的顺序不能反：先 `setUninstallBlocked(false)`，再 `clearDeviceOwnerApp()`，
最后 `removeActiveAdmin()`。已经是 Device Owner 时直接 removeActiveAdmin 会抛 SecurityException，
必须先交出身份，系统才允许卸载。清不掉的情况会在界面上直说，并给 ADB 命令。

卸载按钮要按两下，8 秒反悔窗口，第一下只改文案。部分电视砍掉了卸载界面，
`ACTION_DELETE` 起不来时落回文字提示，不会静默失败。

设置页状态区分「已暂停」（防护还在）和「已关闭」（设备管理员已摘），靠 `OwnerGuard.isAdminActive` 判断。

**忘了密码没有找回入口**，这是故意的。出路只有卸载重装，或 ADB 先摘设备管理器再卸。README 和说明书都写死了这条，因为 Developer 自己也不该有后门。

## 最终权限（设备所有者）

前面所有功能都不需要这个权限。但只有拿到它，孩子的 HOME 键才真的按不动。

### 差别在哪

| 场景 | 没有设备所有者 | 有设备所有者 |
|---|---|---|
| 答题时按 HOME | 闪一下桌面，0.1 到 0.3 秒被拉回，视觉上有破绽 | 毫无反应，键被系统吃掉 |
| 答题时按返回 / 最近任务 | 部分机型能退出去 | 全部失效 |
| 切到 B 站、游戏 | 只有无障碍生效时能发现，可能漏 | 一定弹题，绕不过 |
| 卸载 | 直接在设置里就能卸 | 被拦下，要家长密码 |

### 怎么拿

这个权限安卓不允许应用自己申请，必须由「设备所有者」这个身份授予。两条路，优先第一条。

**路子一：软件内自取（不用电脑，推荐）**

原理是把电脑上那条 `adb` 命令搬到电视里跑：应用自己连本机 `127.0.0.1:5555` 的调试服务，
用标准 adb 协议握手、认证，然后把授权命令发进去。

1. 电视上打开调试开关（见下面「各家电视开调试」）
2. 答题锁设置页 → 往下找到「最终权限」→ 点「只检查调试通道」
3. 显示绿字就点「一键获取最终权限」，电视弹「是否允许调试」时选允许
4. 进度走到「已拿到设备所有者」就成了

**这是标准 adb 协议，不是华为专有的东西。** HDC 是华为自己的调试桥（走 8710 端口），
但智慧屏的开发者选项里那个「HDC 调试」开关打开后，5555 端口上跑的仍然是标准安卓调试。
名字叫 HDC，协议还是 adb，这一点在 KANT-360S 上实测过。

**路子二：电脑或安卓手机灌命令**

```bash
adb connect 192.168.1.23:5555          # 电视 IP 在 设置 → 关于 → 网络信息
adb devices
adb shell dpm set-device-owner com.tvguard/.AdminReceiver
```

出现 `Success` 就成。安卓手机装个「甲壳虫ADB助手」之类的工具，连同一个 WiFi，粘同样的命令，效果一样。

**失败最常见的两个原因：**

| 报错 | 原因 | 处理 |
|---|---|---|
| `already some accounts` | 电视上登着账号（华为账号、Google 账号都算） | 设置里退出账号，再试 |
| `already several users` | 开了多用户 | 设置 → 用户，只留一个 |

### 各家电视开调试

规律是一样的：进「关于本机」，找到「版本号」或「型号代码」，连点 7 次左右，
退回设置就会出现「开发者选项」，里面那个调试开关打开。

| 品牌 | 位置 | 开关名字 |
|---|---|---|
| 华为 / 荣耀智慧屏 | 设置 → 系统 → 关于本机 → 型号代码连点 7 到 12 次 → 通用 → 其他 → 开发人员选项 | HDC 调试 |
| 小米 / Redmi 电视 | 设置 → 账号与安全，或关于里连点版本号 | ADB 调试 |
| 索尼 Android TV / Google TV | 设置 → 系统 → 关于 → 版本号连点 7 次 | USB 调试 / 网络调试 |
| TCL、海信、创维、长虹等 | 设置 → 关于本机 → 版本号连点 7 次 | USB 调试 / ADB 调试 |

找不到「版本号」这一项，就在「关于」里把每一项都连点几次。个别机型藏得深，
需要先开「开发者模式」总开关。开完建议重启一次电视，有的固件不重启不生效。

### 撤销

设置页底部红色按钮 → 「关掉并解除防卸载」，会依次摘掉设备所有者、设备管理员、防卸载标记。

顺序不能反：先 `setUninstallBlocked(false)`，再 `clearDeviceOwnerApp()`，最后 `removeActiveAdmin()`。
已经是设备所有者时直接 `removeActiveAdmin` 会抛 `SecurityException`，必须先交出身份。

摘不掉的情况，用 ADB：

```bash
adb shell dpm remove-active-admin com.tvguard/.AdminReceiver
```

**上过设备所有者的机器，摘身份有可能被系统拒绝，那就只剩恢复出厂设置一条路。** 做之前想清楚。


## 自己编译

需要 JDK 17、Android SDK（platform 34 + build-tools 34）、Gradle 8.4。不用配别的。

```bash
git clone https://github.com/roseryztzhoutong/TvGuard.git
cd TvGuard
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
gradle :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。

**没配密钥时会用 debug 证书签名，这样能直接编出一个装到自己设备上试的包。**
debug 证书是所有开发机通用的公开证书（`~/.android/debug.keystore`，密码就是 `android`），
谁都能用它签一个同包名的假包。短期试用没问题，长期给家人用、或者要发出去给别人装，
就按下面生成自己的密钥。

### 生成自己的发布密钥

```bash
keytool -genkeypair -v -keystore my-release.jks -alias tvguard \
  -keyalg RSA -keysize 2048 -validity 10000
```

在工程根目录建一个 `keystore.properties`，内容如下（这个文件已在 `.gitignore` 里，不会被提交）：

```properties
storeFile=../keys/my-release.jks
storePassword=你的密码
keyAlias=tvguard
keyPassword=你的密码
```

`storeFile` 相对于工程根目录，也可以写绝对路径。有它，`assembleRelease` 就自动用你的证书；
删掉它，退回 debug 证书。

**这把密钥丢了就再也签不出能覆盖安装的包。** 备份到两处以上，别和代码放一起。

> 同一个包名，换签名之后**不能覆盖安装**。已经装过 debug 版或其他证书版本的设备，
> 必须先卸载再装。卸载持有「设备所有者」身份的应用可能被系统拒绝，得先在设置页里
> 走「关掉并解除防卸载」。

### 版本号与 targetSdk

`targetSdk` 刻意停在 28。Android 9 之后前台服务类型、通知权限、分区存储逐版收紧，
升上去要整套真机回归，而这里没有那么多机器。代价是**不能上 Google Play**：
商店要求 targetSdk 至少 31。本项目走 GitHub Releases 直接安装，不受这条限制。

`versionCode` 每次发版加一，`versionName` 是给人看的。

## 题库

**11237 道四选一**，全部带正确答案与解析，路径相对 `app/src/main/`：

| 科目 | 题量 | 说明 |
|---|---|---|
| 语文 | 4146 | 一到六年级，上下册分开 |
| 数学 | 4353 | 同上，另有一个按年级现场生成的算术题生成器 |
| 英语 | 2438 | 同上。一二年级没有英语，不硬凑 |
| 科学 | 300 | 一到六年级每年级 50 道，按年级整册 |

语数英来自 [ChinaTextbookStudyFree](https://github.com/wuwangzhang1216/ChinaTextbookStudyFree)（聪聪学堂，MIT），
按年级学期拆成 12 个文件；科学是本项目自行整理，按年级拆成 6 个文件。

| 文件 | 内容 |
|---|---|
| `app/src/main/assets/bank/g1a.json` … `g6b.json` | 一个年级一个学期的语数英全部题目 |
| `app/src/main/assets/bank/science_g1.json` … `science_g6.json` | 该年级的科学题 |
| `app/src/main/assets/bank/index.json` | 各文件题数，界面直接显示 |

科学不拆上下册，因为各版本教材的科学编排差异太大，按年级合并反而更稳。
选上册时也会把本年级科学一起加载进来。

**出题规则：** 一组题数取「启用科目数」和「连对题数」里的较大值。四科全开就是四道，
语数英科各一道，顺序每次洗牌。关掉科学回到三道，家长把连对调到 6 就排两轮。

实际分布：

| 年级 | 上册 | 下册 |
|---|---|---|
| 一年级 | 615 | 684 |
| 二年级 | 776 | 777 |
| 三年级 | 1333 | 1032 |
| 四年级 | 933 | 842 |
| 五年级 | 1344 | 828 |
| 六年级 | 1103 | 670 |

**重新生成**（换课本版本、加科目都走这条路）：

```bash
# 1. 拉源仓库（45MB，一次就好）
curl -L -o repo.tar.gz https://codeload.github.com/wuwangzhang1216/ChinaTextbookStudyFree/tar.gz/refs/heads/main
mkdir -p ../repo_raw && tar -xzf repo.tar.gz -C ../repo_raw

# 2. 生成题库
python tools/build_bank.py
```

脚本做四件事：剥掉选项的 `A. ` 前缀并把字母答案转成下标、丢弃重复与空选项、过滤题干超 120 字或选项超 55 字的题（电视放不下）、按题干去重。

原始 21473 道里只取 10937 道，因为判断题、填空题、连线题、连词成句在遥控器上没法答，全部剔除。
科学那 300 道是本项目自行整理的，不在这批筛选范围内。

## 答题/锁定期间的按键封闭（v2.5）

### 为什么悬浮窗退居第二

遥控器的 HOME 键和 RECENTS 键由系统 `PhoneWindowManager` 直接处理，不会派发给应用。
悬浮窗收不到，全屏 Activity 在 `onKeyDown` 里也收不到。孩子按一下 HOME 就回桌面，
遮罩还在最上层飘着，人已经跑了。

唯一能吃掉 HOME 的是 `Activity.startLockTask()`：

| 身份 | startLockTask 的效果 |
|---|---|
| Device Owner | 包名进 `setLockTaskPackages` 白名单，HOME / 返回 / RECENTS 全部失效，只能代码退出 |
| 非 Device Owner | 退化为系统「屏幕固定」，HOME / RECENTS 一样失效，但系统保留「返回 + 最近」组合键可解 |

所以 v2.5 把顺序反过来：答题和锁定都先拉全屏 Activity，起不来才退浮窗。
浮窗路径上的 HOME 拦不住，这是已知妥协，日志里会写明。

### 三道闸

1. **锁任务**：`Kiosk.enter()`，Device Owner 时调 `setLockTaskPackages` 后 `startLockTask()`。
2. **按键白名单**：`BaseLockActivity.dispatchKeyEvent()` 先于 View 分发执行，
   只放 `DPAD_UP/DOWN/LEFT/RIGHT`、`DPAD_CENTER`、`ENTER`、`NUMPAD_ENTER`、`TAB`、`UNKNOWN`。
   `KEYCODE_UNKNOWN` 必须放，不少电视遥控器把 OK 键报成 0。其余一律 `return true` 吞掉。
3. **失焦拉回**：`watchdog` 每 500ms 查一次，信号来自 `onUserLeaveHint`、
   `onWindowFocusChanged(false)`、`CLOSE_SYSTEM_DIALOGS` 广播（老 ROM 会发）。
   拉回走两条腿：先 `startActivity(SINGLE_TOP | NEW_TASK)` 复用现有实例，
   200ms 后仍没焦点再 `ActivityManager.moveTaskToFront(getTaskId(), MOVE_TASK_NO_USER_ACTION)`。
   熄屏时不拉，否则关电视会把屏幕重新点亮。

   **v2.7 的关键教训：拉回必须克制，否则会把页面自己掐死在启动阶段。**
   v2.6 把 gap 压到 300ms、watchdog 300ms、还在 `onPause` 里 120ms 后回抢，
   三道信号叠在一起，页面一起手就被自己反复重拉，永远走不完启动流程，
   家长看到的就是「根本什么都不弹」。现在：

   - 只在 `resumed` 为 true（已经在过前台）时才拉；被别的东西盖住时不抢；
   - 起来后 1.5 秒内任何信号都不处理；
   - 两次之间至少 700ms；
   - 删掉了 `onPause` 那条抢回。

   **关键教训（v2.6）**：不能因为 `Kiosk.isOn()` 为 true 就跳过拉回。
   v2.5 就是这么写的，结果华为智慧屏进了 `LOCK_TASK_MODE_PINNED`，HOME 键却照样回桌面，
   拉回逻辑被自己的判断挡在外面，孩子一点就出去了。
   现在不管 kiosk 状态，只要没焦点就拉。

### 家长出口

- 长按返回 3 秒，或长按菜单键 1.2 秒，弹出 `activity_gate` 浮层。
  留两个入口是因为部分电视遥控器根本没有菜单键。
- 密码错照样罚等，规则和主界面一致：4 次起 30 秒，9 次 1 分，14 次 2 分，19 次以上 5 分。
- 答题页验对后 `setSinceLastSec(0)` 直接放行；锁定页验对后 `MainActivity.skipGateOnce = true` 跳设置页。

### 已知边界

- 音量键、HDMI 输入源、语音助手由电视系统直接处理，应用层拦不住。
- 非 Device Owner 时屏幕固定理论上有组合键可退，电视遥控器一般按不出来。
- `setLockTaskPackages` 传的是**包名**不是组件名，同包其他 Activity 才能一起被放行。
- `stopLockTask()` 必须在发起 `startLockTask()` 的同一个 Activity 实例里调。

## 双形态答题（浮窗 / 全屏，v2.7 会自己记路）

`GuardService.attemptQuiz()` 的顺序由 `Prefs.getFullscreenOk()` 决定，第一次默认 true：

```
全屏页优先 ──1.2s 回查 isShowing()──► 没到前台 ──► setFullscreenOk(false)
      │                                        └──► 立刻改挂浮窗，以后都走浮窗
      └──► 到前台 ──► 记「全屏答题页，已显示」，setSinceLastSec(0)
```

| 场景 | 处理 |
|---|---|
| `startActivity` 抛异常 | `setFullscreenOk(false)`，改挂浮窗 |
| 1.2s + 1s 两次回查 `QuizActivity.isShowing()` 都是 false | 同上，并把后台建起来的僵尸页 `closeFullScreen` 收掉 |
| 没有悬浮窗权限且全屏页也不行 | 每 6 秒换一条路重试，**永不放弃**；连败 3 次在电视上发一条通知说明原因 |
| `wm.addView` 抛异常 | 记失败，重试另外那条路 |

### alive 是假信号，必须带有效期

`QuizActivity.alive` / `LockActivity.alive` 是静态标记，**Activity 在 `onCreate` 里崩掉时不会走
`onDestroy`，标记会永久留在 true**。v2.6 的 tick 就是被这个假标记卡死的：服务以为题一直挂着，
于是 `tickAddSec()` 不走、`attemptQuiz()` 永远轮不到，孩子可以白看一整晚。

现在对外只暴露 `isShowing()`：`alive && 最近 4 秒内真的到过前台`，
`lastFrontAt` 由 `onAliveSignal()`（`onResume` / `onWindowFocusChanged(true)`）刷新，
**`onCreate` 里故意不写**，因为「建起来了」不等于「到前台了」。

### 题库没准备好不能直接放行

`QuizActivity.waitForBank()` 每 300ms 看一次 `bank.size()`，最多等 8 秒。
以前 `next()` 返回 null 会被判成「题库空 → 1.2 秒后自动放行」，刚开机那一次弹题等于白弹。

判题逻辑在 `QuizController` 里，两条路共用。`QuizActivity` 通过 `GuardService.sharedBank`
复用已解析的题库，避免主线程重新解 JSON。全屏页在跑的时候 tick 会直接 return，不累加观看时长。

风险提示：Android 10 起限制后台启动 Activity。`targetSdk 28` 在 Android 10 上仍按旧行为放行，
Android 11 及以上可能被拦。被拦时会自动改走浮窗，能答题但拦不住 HOME。

## 浮窗通道的两级结构（v3.0）

真机上最硬的一条约束：**华为智慧屏的设置里根本没有「悬浮窗」权限项**。
`Settings.canDrawOverlays()` 永远是 false，家长想给也给不了。
只靠全屏 `startActivity` 又会被 Android 10 的后台启动限制静默拦掉，
于是「家长开着 B 站，题一次都不弹」。

v3.0 的解法是加一条不需要那个权限的通道：`TYPE_ACCESSIBILITY_OVERLAY`。

```
overlayType():
  1. Settings.canDrawOverlays() == true  → TYPE_APPLICATION_OVERLAY
  2. A11y.ready() == true                → TYPE_ACCESSIBILITY_OVERLAY
  3. 都不成立                            → -1，只能拉全屏页
```

无障碍浮窗的三个附带好处，都是这次真机上要的：

| 好处 | 原因 |
|---|---|
| 不需要 SYSTEM_ALERT_WINDOW | 该窗口类型由无障碍服务持有，系统不检查那个 appop |
| 永远盖在最上层 | 浮窗不挂在任务栈上，孩子按 HOME 只是把桌面露在浮窗下面，题还在 |
| 进程不容易被冻 | 被系统绑定的无障碍服务优先级高，电视省电策略基本不动它 |

浮窗和全屏页的顺序在 `attemptQuiz()` 里也反过来了：**先浮窗，浮窗不行才全屏**。
以前的顺序是「全屏优先」，理由是能进锁任务模式吃掉 HOME；但全屏页在后台根本起不来，
优先级再高也是零。现在浮窗优先，能盖上去才有后面的事。

`A11y` 只做两件事：保持连接、提供 WindowManager。`canRetrieveWindowContent=false`，
不读屏幕内容，`onAccessibilityEvent` 是空实现。

### 无障碍按键转接（给「浮窗画得出来但吃不到按键」的 ROM）

`a11y_service.xml` 里开了 `canRequestFilterKeyEvents`。`A11y.onKeyEvent()` 转给
`GuardService.onA11yKey()`，而后者只在一种情况下接管：

```java
if (!quizShowing || quizView == null) return false;
if (quizView.hasWindowFocus()) return false;   // 窗口自己能收，绝不插手
```

接管时方向键自增一个 `a11ySel` 并 `requestFocus()`，确认键走 `performClick()`。
不加这一条的话，那些 ROM 上孩子会卡在一个按不动的题上，比不弹还糟。

另外 `attemptQuiz()` 会在浮窗挂上 2.5 秒后回查 `hasWindowFocus()`，
拿不到焦点就把浮窗撤掉改走全屏页（`focusFallbackUsed` 保证一次弹题只降级一次，不来回抖）。

## 计时为什么会在后台停住（v2.8）

真机反馈里最典型的一条：「孩子开着 B 站看了一小时，一次都没弹；家长一打开答题锁这个界面，时间立刻满格弹题。」

这不是计时代码写错了，是电视把后台进程冻住了。冻结期间 handler 一秒都不跑，
而 v2.7 之前的记法是「跑一次 +1 秒」，被冻住的时间就永久丢失。
哪些时间被丢了，取决于这台电视多久冻一次，所以现象极不稳定，
同一台机器今天弹、明天不弹。

v2.8 改成按真实时间记账：

```java
long raw   = nowE - lastTickE;                    // elapsedRealtime，含深睡
long awake = nowU - lastTickU;                    // uptimeMillis，深睡不走
long d     = Math.min(raw, awake);                // 只算设备醒着的那段
int  delta = clamp(d / 1000, 1, 600);             // 单片最多补 10 分钟
prefs.tickAddSec(delta);
```

取两个时钟的较小值是关键。只用 `elapsedRealtime` 会把「电视关了半小时」也算成观看时长，
一开机就冲爆每日上限；只用 `uptimeMillis` 又会漏掉「亮屏但进程被冻结」的场景，
而那正是我们要补的。两者取小刚好把两种情况分开。

配套还有两件事：

| 手段 | 作用 |
|---|---|
| `AlarmManager.setExact(ELAPSED_REALTIME_WAKEUP)` 每 60 秒一次，`PendingIntent.getForegroundService` | 进程被冻或被杀了也能自己醒过来补时，不依赖用户打开界面 |
| `Prefs.beat()` 心跳 | 设置页和诊断页能直接显示「上次心跳几秒前」，把「后台没在跑」这件事变成看得见的数字 |

`tickAddSec` 的落盘阈值从「每 15 次」改成「每累计 15 秒」，补时的时候不会被拆散。
`cUsed` / `cSince` 改成 `static`，这样诊断页和设置页读到的是实时值，不是最多滞后 15 秒的磁盘值。

### 拉回逻辑的守卫条件必须用 everFront，不能用 resumed

v2.7 的 `relaunch()` 有一句 `if (!resumed) return;`。按 HOME 键时系统先发 `onPause`
（resumed 变 false）再发 `onUserLeaveHint`，所以按键那一刻守卫正好是关的，
拉回代码一次都没执行过。真机表现就是「按桌面键直接回主界面」。

v2.8 换成 `everFront`：只要这个界面真正到过一次前台就是 true，之后不再回退。
启动期（还没到过前台）依然不抢，避免 v2.6 那个「把自己掐死在启动阶段」的坑。
另外补了 `onStop` 作为第三路信号，`onPause` 只可能是被对话框盖住，`onStop` 才代表真的离开了屏幕。

## 多分辨率适配

电视的 DPI 报得极乱：同样 1080p 屏，有的报 320dpi（960×540dp），有的报 160dpi（1920×1080dp），4K 机型更夸张。按 dp 写死尺寸会让同一套界面在不同电视上一半太大一半太小。

`Ui.wrap()` 的做法是按短边像素反推 density：

```
densityDpi = 160 * 短边像素 / (540 * 缩放系数)
```

于是 720p（213dpi）、1080p（320dpi）、4K（640dpi）全部渲染成 960×540 的逻辑画布，布局只需按这个尺寸设计一次。`Ui` 在 `Activity.attachBaseContext` 和 `Service.attachBaseContext` 里统一套用，界面缩放三档（0.9 / 1.0 / 1.2）改的就是这个系数。

配套约束：
- 所有页面都要可滚动，长题干长选项靠 `ScrollView` 兜底；
- 字号按文字长度动态缩一档，英语整句选项也放得下；
- 答题界面只信逻辑画布，不依赖厂商状态栏高度。

## 学习报告

`Stats` 按天记录：答题数、答对、答错、观看秒数、分科目题数与答对数。存成一行一天的紧凑文本，只留最近 200 天。

`Report` 同时产出两种形态：纯文本（电视上看）和 HTML（手机浏览器看）。

三种交付方式：
1. 电视端 `ReportActivity` 直接看；
2. `LanReportServer` 在局域网开一个小网页（默认 8666，被占则顺延），家长手机浏览器打开 `http://电视IP:8666` 即可，数据不出家门；
3. 导出 HTML 到应用私有目录，U 盘拷走。

自动提醒：`GuardService` 每周一提醒上周报告、每月 1 号提醒上月报告，同一周期只提醒一次。

## 结构

| 文件 | 作用 |
|---|---|
| `GuardService` | 前台服务，每秒计时，满周期弹遮罩，统计埋点，周期报告提醒 |
| `MainActivity` | 家长设置页，数字键盘密码保护，密码策略与罚等 |
| `SetupActivity` | 年级与学期选择，首次启动引导 |
| `CompatActivity` | 兼容性自检：悬浮窗权限、遥控器浮窗按键实测、品牌适配路径 |
| `QuizController` | 答题逻辑唯一实现，浮窗与全屏页共用，避免两套判题走偏 |
| `QuizActivity` | 全屏答题页。悬浮窗画不出来时的兜底，不依赖任何特殊权限 |
| `LockActivity` | 全屏锁定页。同上，用于连错罚等与今日时长用完 |
| `ExitActivity` | 退出与关闭：停止 / 解除防卸载 / 卸载，进入前二次验密码 |
| `ReportActivity` | 学习报告页，可切周/月，可开手机查看与导出 |
| `QuestionBank` | 多文件题库加载，打乱选项，轮次管理 |
| `MathGen` | 现场生成口算题与几何题 |
| `Stats` | 按天统计与区间聚合 |
| `Report` | 文本与 HTML 报告生成 |
| `LanReportServer` | 局域网网页服务 |
| `ReportHub` | 报告数据与服务的公共入口 |
| `Ui` | 分辨率归一化与界面缩放 |
| `BankInfo` | 题库索引，界面显示题数 |
| `Prefs` | 参数、状态、年级、统计口径持久化 |
| `BootReceiver` | 开机自启 |
| `AdminReceiver` / `OwnerGuard` | 设备管理器与设备所有者，防卸载 |
| `tools/build_bank.py` | 题库导入脚本 |
| `tools/make_icons.py` | 各密度图标与 TV banner 生成 |

## 关键实现点

- 悬浮窗用 `TYPE_APPLICATION_OVERLAY`，**不能加 `FLAG_NOT_FOCUSABLE`**，否则遥控器方向键作用不到遮罩上。
- 返回键要在根布局和每个选项按钮上双重拦截，因为焦点在子 View 时按键不会冒泡到父级。
  v2.5 起主路径是 `BaseLockActivity.dispatchKeyEvent()` 的整体白名单，这层双保险留在浮窗兜底路径上。
- 答题期间每 700ms 检测焦点，被 HOME 切走后重新拉回前台；主路径靠锁任务模式，基本用不上这条。
- 锁任务模式下不能指望 `onKeyDown` 拦 HOME，HOME 压根不会派发到应用，必须靠 `startLockTask()`。
- 计时的状态写 SharedPreferences，`rolloverIfNewDay()` 每次读写前调用，保证跨天清零。
- 每次出题要扫全库问「这题答对过几次」，所以答题历史必须缓存在内存里，否则每题上千次解析会卡死。
- 题库解析和报告生成全部放后台线程，`QuestionBank` 的公开方法加 `synchronized`。
- `targetSdk 28`，避免高版本系统对前台服务和后台启动 Activity 的限制（Lint 会报 ExpiredTargetSdkVersion，那是应用商店的规则，电视侧载不受影响）。

## 已知限制

- 孩子进系统设置「强行停止」可以停掉服务，安卓通病，只能靠设备所有者提高门槛。
- 改系统时间会让当日统计提前重置。
- 纯鸿蒙智慧屏（HarmonyOS NEXT）装不了 APK，只能外接安卓盒子。
- 遥控器的音量键、HDMI 输入源键、语音键由电视系统直接处理，答题期间也拦不住。
- **没有设备所有者时，HOME 键吃不掉**。屏幕上会闪一下桌面，靠失焦拉回抢回来，视觉上有破绽。
  拿到设备所有者后进入真锁任务，HOME 由系统自己拦，这个问题就不存在了。
- 上不了 Google Play。`targetSdk` 是 28，商店要求 31 以上。侧载安装不受影响。
- Android 14 及以上系统装这个包时，会弹一句「此应用专为旧版 Android 打造」，点「仍然安装」即可。
  这是 `targetSdk` 偏低的提示，不是兼容性问题。
- 答题时视频的进度会被全屏页打断，答完要用户自己按播放。这是换「孩子出不去」的代价。
- 非 Device Owner 时锁任务会退化成屏幕固定，理论上存在系统组合键可解。
- 答题的那几十秒里视频还在播声音，孩子可以靠听。要堵住得抢音频焦点，兼容性风险高，暂未实现。

## 第三方资源

题库来自 ChinaTextbookStudyFree，MIT 协议，版权声明见 `THIRD-PARTY-NOTICE.md`。
