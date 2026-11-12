# My PyCharm

个人 PyCharm 插件合集，功能对齐 [vscode-my-vscode](../../vscode-my-vscode)（VS Code 扩展 `my-vscode`）。五个功能：

1. **Python 文件路径补全** — `.py/.pyw/.pyi` 文件里输入 `[[` 自动提示工作区全部文件（相对内容根的路径，上限 2000，排除 `node_modules/.git/__pycache__/.venv/venv/dist/out/tmp/.vs`）；`[[./`、`[[../`、`[[.`、`[[..` 或行首 `./`、`../a/b/` 按当前文件目录提示相对路径；目录项带 `/` 尾巴、排前面；回车插入时把光标后的半截词一并替换（类 Obsidian wiki-link 体验）。
2. **分离 git 仓库绑定** — 自动发现各根目录下 `*-gl.sh` 包装脚本中的 `--git-dir` / `--work-tree`（注释行忽略，纯转发脚本自然跳过），把 `.git` 外迁的仓库接进 PyCharm 版本控制体系：提交面板直接可用（变更列表、差异视图、提交），外加刷新/重新加载绑定/切换分支/推送/拉取/同步/发布分支/管理远程/暂存与放弃（选中或全部）。目录内已有自己的 `.git`（自管仓库）时让给内置 Git 集成。
3. **提交图表工具窗** — 跟随当前编辑器文件所属仓库，`git log --graph -n 300` 渲染（ASCII 图 + 主题 + `短哈希 · 相对时间 · 作者`）；双击提交弹出变更文件列表，选文件查看差异（父提交 ↔ 该提交）。
4. **新建同名 MD 文件** — 项目视图/编辑器右键，在同目录创建 `<base>.md`（已存在则直接打开）并跳转。
5. **生成同名文件** — 项目视图/编辑器右键，在同目录生成 `<base>_<序号>.<ext>`（取最小未占用序号：`test.py` → `test_0.py` → `test_1.py`…）并打开；设置里可选「复制原文件内容」，默认创建空文件。

## 安装

**方式一（推荐，一次配置以后在线更新）**：仓库已内置 `updatePlugins.xml` 自定义插件仓库——
1. Settings → Plugins → ⚙ → **Manage Plugin Repositories...** → 添加：
   `https://cdn.jsdelivr.net/gh/alvisliu818/pycharm-my-pycharm@main/updatePlugins.xml`
   （jsDelivr CDN，国内可直连无需代理；若连不上再试 raw 备选：`https://raw.githubusercontent.com/alvisliu818/pycharm-my-pycharm/main/updatePlugins.xml`，需要 IDE 配代理）
2. Plugins → Marketplace 页搜 **My PyCharm** → Install → 重启；以后新版本直接在 Updates 里出现。
3. 若之前用磁盘方式装过：先卸载旧的再从 Marketplace 装（只这一次）。

> 提示：若添加仓库时报连接错误，多半是 IDE 没走代理。备选方案：Settings → Appearance & Behavior → System Settings → HTTP Proxy → Manual → Host `127.0.0.1` Port `7897` → Check connection 验证 `https://raw.githubusercontent.com/` 通过后再用 raw 地址。

**方式二（磁盘安装）**：Settings → Plugins → ⚙ → **Install Plugin from Disk...** → 选 `my-pycharm-0.2.4.zip`，重启；若已装同版本再装会弹「Cannot update plugin」，卸载重启后重装即可。

目标平台：PyCharm 2026.3（build 263+）。2026.3 起平台类是 Java 25 字节码，插件随平台要求编译为 release 25。

## 配置

设置 → Tools → **My PyCharm**：

- `自动发现`（对应 myVscode.autoDiscover，默认开）：解析项目基目录与各内容根下 `*-gl.sh`；
- `新建同名 MD 文件后在下方分栏打开`（默认开）：右键新建同名 MD 后，若当前有编辑器则在编辑器下方分栏打开新 MD（无编辑器时回退普通打开）；关闭则保持普通打开；
- `生成同名文件时复制原文件内容`（默认关）：开启后右键生成同名文件会带上原文件内容，关闭则创建空文件；
- 绑定表格（对应 myVscode.bindings）：工作树 / git 目录 / 名称 / 强制接管（force：跳过自管检测强制接管）。

设置持久化在项目级 `MyPycharmSettings.xml`。动作入口：VCS 菜单 → My Pycharm 分组；暂存/取消暂存/放弃更改在提交面板右键菜单。

## 构建

### Gradle（首选，已打通）

```powershell
.\gradlew compileJava   # 或 buildPlugin / selfTest
```

- 工具链：Java 25 = PyCharm 自带 JBR（`gradle.properties` 里 `org.gradle.java.installations.paths` 指向它；PATH 上的 JDK 21 编不了 class 69 的平台 jar）；
- `repositories { intellijPlatform { defaultRepositories() } }` **必写**——其中 `localPlatformArtifacts()` 注册的本地 Ivy 仓库负责解析 `localIde:<产品>:<版本>`；漏写时解析回落到全局 init.gradle 的远程镜像并 404（首次失败的原因）；
- 2.20.1 对 2026.3 新布局的缺口：`local()` 的平台 classpath 剔除了 lib 下四个 `intellij.platform.vcs.*.jar`，而 bundledPlugin 描述符只涵盖插件自己的 lib/modules —— 已用 `compileOnly(files(...))` 显式补回（不入产物）；
- `local()` 指向 `D:/programs/PyCharm2026.3`（无空格 junction，带空格路径会让 localIvy 解析异常）；
- 产物：`build/distributions/my-pycharm-0.1.0.zip`（含 searchable options，已实机验证）。

### 脚本（免 Gradle 备选）

```powershell
pwsh tools\build.ps1          # 编译 + 冒烟测试 + 打包 my-pycharm-<version>.zip（免 Gradle 免网络）
```

- 编译基准 = 本机 `D:\programs\PyCharm 2026.3`（`lib\*` + 捆绑插件 `platform-vcs-plugin` 的 modules）；
- **必须用 PyCharm 自带 JBR 的 javac（JDK 25）**；产物与 Gradle 构建一致（少 searchable options）。

## 验证状态

- **实机验证通过**（沙箱 PyCharm 2026.3 + 隔离 config/plugins，idea.log 断言；脚本构建与 Gradle 构建两种产物各验一轮）：插件加载、`*-gl.sh` 绑定发现、自管过滤、目录映射（`VCS Root: [my-pycharm]`）、ChangeProvider 被平台调度并跑通 `git status --porcelain -z -b` 解析；无插件异常栈、无扩展点注册错误。
- **纯逻辑冒烟测试**（`PureSelfTest`，对齐 vscode-my-vscode 的 `test/smoke.mjs`）：真实包装脚本解析（myobsidian-gl.sh 命中、gl.sh 转发跳过）、Code 根全部 gl 脚本发现列表、合成 status 解析（中文路径/重命名/双状态/删除）、真实分离仓只读探测、补全上下文判定。
- **未逐项点验**（编译期已对 2026.3 真实 API 签名校验，但无 GUI 交互验证）：提交面板点击提交、补全弹出交互、图表双击差异、远程管理弹窗链、「下方分栏打开」的交互效果（`EditorWindow.split(JSplitPane.VERTICAL_SPLIT, ...)` 已按 2026.3 源码语义接入，分栏代码随 0.2.1 实机加载验证无异常）。如遇问题看 idea.log 里 `[my-pycharm]` 前缀日志。

## 与 vscode-my-vscode 的对照

| my-vscode | My PyCharm |
| --- | --- |
| `myVscode.autoDiscover` / `myVscode.bindings` | 设置页同义配置（`MyPycharmSettings.xml`） |
| SCM 面板（暂存/更改两组、输入框提交） | 平台提交工具窗（变更列表 + 提交；选中即暂存语义） |
| `myVscode.commit/commitAll` | 提交面板 Commit（CheckinEnvironment：勾选即暂存后 commit） |
| stage/unstage/stageAll/unstageAll/discard(discardAll) | 提交面板右键动作 + VCS 菜单全部动作（discard=clean+checkout，先确认） |
| checkout/push/pull/sync/publish/remotes | VCS → My Pycharm 同名动作（git CLI，120~180s 超时，通知回显） |
| quickDiff（HEAD:g 内容 URI） | DiffProvider（HEAD 内容）+ ChangeProvider BeforeRevision 差异 |
| 提交图表 TreeView | 提交图表工具窗（JBList 渲染，双击→文件列表→diff） |
| `myVscode.newSameNameMd` | ProjectView/Editor 右键同名动作 |
| —（插件独有） | 右键「生成同名文件」（`_0`/`_1`… 序号拼接，可设置复制原内容，默认空文件） |
| 输出通道日志 | idea.log（`[my-pycharm]` 前缀）+ 气泡通知 |

差异说明：VSCode 的「暂存/工作树」两组在 IntelliJ 里表现为同一变更列表（勾选提交=暂存并提交）；状态栏分支/同步按钮由 VCS 菜单与工具窗承担。
