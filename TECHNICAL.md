# MyBatis Log Restorer — 技术文档

---

## 1. 项目概览

| 属性 | 值 |
|---|---|
| **项目名称** | MyBatis Log Restorer |
| **项目类型** | IntelliJ IDEA 平台插件 |
| **插件 ID** | `com.seekxu.mybatis-log-plugin-free` |
| **版本** | 2.0.0 |
| **作者** | seekxu (Forked from starxg) |
| **许可证** | GPL v2 |
| **目标平台** | IntelliJ IDEA 2024.1 ~ 2026.2 (sinceBuild=241, untilBuild=262.*) |
| **语言/框架** | Java 17, IntelliJ Platform SDK 2.18.1 |
| **构建工具** | Gradle 9.6.1 |

### 1.1 核心功能

将 MyBatis 框架输出的两行式 SQL 日志：
```
Preparing: UPDATE mp_user SET name=? WHERE id=?
Parameters: 张三(String), 1(Long)
```
还原为完整可执行的 SQL：
```
UPDATE mp_user SET name='张三' WHERE id=1
```
并展示在 IDEA 底部的独立工具窗口中，支持语法高亮、格式化、导航等。

### 1.2 主要特性

- **SQL 日志还原** — 用实际参数值替换 `?` 占位符
- **独立 ToolWindow** — 固定在底部，点击图标打开面板
- **语法高亮** — INSERT/DELETE/UPDATE/SELECT/WITH/EXPLAIN 不同颜色
- **SQL 格式化** — 内置 Hibernate BasicFormatter，可切换
- **SQL 导航** — 上一条/下一条跳转
- **关键词过滤** — 按关键词忽略不相关日志
- **前缀自定义** — 支持修改 `Preparing:` / `Parameters:` 前缀适配不同日志框架
- **多线程并发** — 支持多线程日志交错，按 logger 名正确配对
- **日志格式兼容** — 三级回退提取 source，适配多种自定义日志格式

---

## 2. 架构

### 2.1 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      IntelliJ IDEA                          │
│                                                              │
│  ┌───────────────────┐     ┌─────────────────────────────┐  │
│  │   Console Output   │────▶│  MyBatisLogConsoleFilter   │  │
│  │   (IDEA 控制台)    │     │  (拦截 + 解析 + 还原 SQL)   │  │
│  └───────────────────┘     └──────────────┬──────────────┘  │
│                                           │                  │
│                                           ▼                  │
│                                 ┌──────────────────────┐    │
│                                 │   MyBatisLogManager  │    │
│                                 │  (单例 / 状态管理)    │    │
│                                 └──────────┬───────────┘    │
│                                            │                 │
│                                            ▼                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │           ToolWindow "MyBatis Log Restorer"          │    │
│  │  ┌────────────────────────────────────────────────┐  │    │
│  │  │  ActionToolbar  │  ConsoleView (SQL Display)   │  │    │
│  │  │  ┌────────────┐│  ┌────────────────────────┐  │  │    │
│  │  │  │ Rerun      ││  │ -- 1 -- UPDATE ...     │  │  │    │
│  │  │  │ Stop       ││  │ UPDATE ... WHERE ...   │  │  │    │
│  │  │  │ Settings   ││  │ -- 2 -- INSERT ...     │  │  │    │
│  │  │  │ Prev/Next  ││  │ INSERT INTO ...        │  │  │    │
│  │  │  │ Format     ││  │ ...                    │  │  │    │
│  │  │  │ Clear      ││  │                        │  │  │    │
│  │  │  └────────────┘│  └────────────────────────┘  │  │    │
│  │  └────────────────────────────────────────────────┘  │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 数据流

```
MyBatis 日志输出
    │
    ▼
IDEA Console 输出流
    │
    ▼
MyBatisLogConsoleFilter.applyFilter(line, length)
    │
    ├── 检查 MyBatisLogManager.isRunning()
    ├── 关键词过滤
    ├── 匹配 "Preparing:" → 暂存 pendingSqls[source]
    ├── 匹配 "Parameters:" → 按 source 匹配 → 触发解析
    │
    ├── [参数不足] → 启动 paramBuffer 续行缓冲，累积多行值 (XML/JSON)
    │   └── 每行: 跳过空白行 → 追加到 paramBuffer → 重新解析
    │
    ├── [参数充足] → 调用 processSql()
    │
    ▼
MyBatisLogConsoleFilter.parseParams(line)
    │  移除所有 \r → trim() 去首尾空白
    │  深度追踪分词 (depth-tracking tokenizer):
    │   → 按逗号分割，仅在 depth=0 (括号外) 时切分
    │   → 分隔符检测: 逗号后空白序列含空格(0x20) 或 换行符 (\n) 即判定为参数分隔
    │   → 容忍控制台换行打断分隔符: ",\n   " 或 ",\n" (逗号和空格跨行)
    │   → 正确处理 XML/JSON 值内部的逗号 (depth>0 时不切分)
    │   → 识别 "(TypeName)" 类型标记，提取值和类型
    │   → 返回 Queue<Entry<值, 类型>>
    ▼
MyBatisLogConsoleFilter.parseSql(sql, params)
    │  O(n) 扫描: 直接构建新 StringBuilder (非 deleteCharAt/insert 位移)
    │  遇到 ? 占位符 → 从 params 队列取参数 → 追加到结果
    │  队列空时保留原 ? (防止参数不足导致错误 SQL)
    │  String/Date 类型加单引号: 'value'
    │  数字/null 类型直接替换
    ▼
MyBatisLogManager.println(prefix, sql, rgb)
    │
    ├── 根据 SQL 类型确定颜色 (INSERT/DELETE/UPDATE/SELECT)
    ├── 可选格式化 (BasicFormatter, Windows 下使用 System.lineSeparator())
    ├── 换行符规范化: 移除所有 \r → 合并连续 \n → 防止双空行
    └── 输出到 ConsoleView
```

### 2.3 类关系

```
MyBatisLogConsoleFilterProvider ──创建──▶ MyBatisLogConsoleFilter
        │                                        │
        │  注册为 ConsoleFilterProvider          │  读取
        │                                        ▼
        │                                MyBatisLogManager (单例)
        │                                        │
        │                                        ├── ConsoleViewImpl (显示 SQL)
        │                                        ├── ActionToolbar (左侧工具栏)
        │                                        ├── ToolWindow (底部工具窗口)
        │                                        └── Disposable (生命周期管理)
        │
MyBatisLogToolWindowFactory ──创建──▶ MyBatisLogManager (首次打开时)
        │
MyBatisLogAction ──重启/激活──▶ MyBatisLogManager.recreateInstance()
        │
Action 类 (RerunAction, StopAction, SettingsAction, CopySqlAction, ...)
        │  注入 MyBatisLogManager 引用
        └── 操作 MyBatisLogManager 的 run/stop/settings
```

---

## 3. 目录结构

```
mybatis-log-plugin-free/
├── src/
│   ├── main/
│   │   ├── java/com/starxg/mybatislog/
│   │   │   ├── MyBatisLogConsoleFilter.java        # 核心: 控制台日志过滤 + SQL 解析还原
│   │   │   ├── MyBatisLogConsoleFilterProvider.java # Filter 提供者, 注册到 IDEA
│   │   │   ├── BasicFormatter.java                 # SQL 格式化器 (源自 Hibernate)
│   │   │   ├── Icons.java                          # 图标常量
│   │   │   ├── gui/
│   │   │   │   ├── MyBatisLogManager.java          # 核心管理器 (单例, UI 构建, 状态)
│   │   │   │   ├── MyBatisLogToolWindowFactory.java # ToolWindow 工厂
│   │   │   │   ├── SettingsDialogWrapper.java      # 设置对话框
│   │   │   │   ├── SettingsDialogWrapper.form       # 设置对话框 UI 布局
│   │   │   │   └── DonateDialogWrapper.java         # 捐赠对话框
│   │   │   └── action/
│   │   │       ├── MyBatisLogAction.java           # 主入口动作 (Tools 菜单 + 右键)
│   │   │       ├── RerunAction.java                # 重启插件
│   │   │       ├── StopAction.java                 # 停止监听
│   │   │       ├── SettingsAction.java             # 打开设置
│   │   │       ├── PreviousSqlAction.java          # 上一条 SQL
│   │   │       ├── NextSqlAction.java              # 下一条 SQL
│   │   │       ├── PrettyPrintToggleAction.java    # 格式化开关
│   │   │       ├── ClearAllAction.java             # 清空
│   │   │       ├── DonateAction.java               # 捐赠
│   │   │       ├── JumpSqlAction.java              # SQL 导航抽象基类
│   │   │       └── CopySqlAction.java             # 复制当前条目 SQL
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   ├── plugin.xml                      # 插件描述符
│   │       │   └── pluginIcon.svg                  # 插件图标
│   │       ├── icons/
│   │       │   ├── ibatis.svg                      # MyBatis 图标
│   │       │   ├── coffee.svg                      # 捐赠图标
│   │       │   └── prettyPrint.svg                 # 格式化图标
│   │       └── images/
│   │           ├── alipay.png                      # 支付宝收款码
│   │           └── wechatpay.png                   # 微信收款码
│   └── test/
│       └── java/com/starxg/mybatislog/
│           └── MyBatisLogConsoleFilterTest.java     # 单元测试 (parseParams/parseSql)
├── build.gradle                                     # Gradle 构建脚本
├── settings.gradle
├── gradlew / gradlew.bat                            # Gradle Wrapper
└── README.md
```

---

## 4. 核心类职责详解

### 4.1 MyBatisLogManager (核心管理器)

**文件**: `src/main/java/com/starxg/mybatislog/gui/MyBatisLogManager.java`

**职责**: 单例管理整个插件的运行时状态和 UI。

**关键方法**:

| 方法 | 说明 |
|---|---|
| `createInstance(Project)` | 创建实例（如已存在且有效则直接返回） |
| `recreateInstance(Project)` | 强制销毁旧实例并重建（用于 Rerun） |
| `getInstance(Project)` | 获取当前实例（如果 ToolWindow 不可用则 dispose 并返回 null） |
| `run()` | 开始监听 MyBatis 日志 |
| `stop()` | 停止监听 |
| `resetCounter()` | 重置 SQL 序号计数器（清空时调用） |
| `println(logPrefix, sql, rgb)` | 输出还原后的 SQL 到 ConsoleView；换行符规范化 (移除 \r、合并连续 \n)；超过 10000 行时自动 trim 最旧 20% |
| `debug(msg)` | 输出调试日志到 ConsoleView（`DEBUG` 开关控制，默认关闭），用于排查解析问题 |
| `dispose()` | 清理资源（移除 Content、停止监听） |

**重要设计决策**:
- **`createInstance` vs `recreateInstance`**: `createInstance` 是幂等的（已存在就复用），`recreateInstance` 强制重建
- **`dispose()` 中必须调用 `removeContent`**: 防止 Rerun 时产生重复 Tab
- **构造函数中 `removeAllContents(true)`**: 确保重新打开面板时不会有残留内容
- **`running = true` 在构造函数末尾**: 面板一打开即自动开始捕获日志
- **`MAX_LINE_COUNT = 10000`**: 超过阈值时删除最旧 20% 行，保留最近 8000 行，防止内存泄漏
- **换行符规范化**: `println` 输出前执行 `replace("\r", "").replaceAll("\\n{2,}", "\n")`，消除 Windows 下 BasicFormatter 使用 `System.lineSeparator()` 造成的 `\r\n\n` 双空行问题

**线程安全**: 构造函数应在 EDT 调用。`println` 由 ConsoleFilter 调用，也需在 EDT 上。`keywords` 使用 `CopyOnWriteArrayList` 保证并发读写安全。

### 4.2 MyBatisLogConsoleFilter (日志过滤器)

**文件**: `src/main/java/com/starxg/mybatislog/MyBatisLogConsoleFilter.java`

**职责**: 拦截 IDEA 控制台输出，解析并还原 MyBatis SQL。

**核心解析逻辑**:
```
applyFilter(line)
  ├── 提取 source（三级回退: logger 名 → [thread] → 完整前缀）
  ├── 匹配 "Preparing:" → 暂存 pendingSqls[source]
  ├── 匹配 "Parameters:" → 按 source 匹配并配对
  │   ├── 参数不足 → 启动 paramBuffer 续行缓冲
  │   │   └── 每行: 跳过空白行 → 追加 → 重新 parseParams 直到参数充足
  │   └── 参数充足 → processSql() → manager.println()
  ├── 每 5s 清理一次过期 Preparing
  └── 其他行: 忽略

handleContinuation(line)
  ├── 终止条件: 遇到 Preparing/Parameters/<= 行 → flush 缓冲并处理
  │   ├── flush 时参数不足也会处理 (parseSql 保留剩余 ?)
  │   └── Preparing/Parameters 终止符不消费行 (返回 false)，让该行继续正常匹配
  └── 续行: 追加到 paramBuffer → 重新 parseParams → 参数充足则处理

parseSql(sql, params)
  ├── O(n) 扫描: 遍历 SQL 中 ? 占位符
  ├── 从 params 队列取参数 (队列空则保留 ?)
  ├── String/Date 类型加单引号: 'value'
  └── 数字/null 类型直接替换: value

parseParams(line)
  ├── 移除所有 \r → trim() 去首尾空白
  ├── 深度追踪分词 (depth-tracking tokenizer):
  │   ├── 按逗号分割，仅在 depth=0 (括号外) 时切分
  │   ├── 分隔符检测: 逗号后空白序列含空格(0x20) 或 换行符(\n)
  │   ├── 正确处理 XML/JSON 值内部的逗号 (depth>0 时不切分)
  │   ├── 识别 "(TypeName)" 类型标记，提取值和类型
  │   └── 支持 "null" 关键字 → (null, null)
  └── 返回 Queue<Entry<值, 类型>>
```

**关键设计**:
- **Map 多源缓存**: `ConcurrentHashMap<String, PendingSql>` 按 source 缓存多条 Preparing，支持多线程交错
- **10 秒超时**: `PendingSql` 携带时间戳，超时自动清理，防止内存泄漏
- **ThreadLocal 复用 Matcher**: 避免每行日志创建新正则对象
- **续行缓冲**: `paramBuffer` 累积多行参数值，每行跳过空白行，累积后重新解析
- **深度追踪分词**: 用括号深度 (depth) 判断逗号是否为参数分隔符，正确处理含逗号的 XML/JSON 值
- **弹性分隔符检测**: 逗号后空白序列含空格(0x20)或换行符(`\n`)即判定为参数分隔，容忍控制台换行打断
- **O(n) SQL 还原**: `parseSql` 使用 StringBuilder append 构建结果，零字符移动开销，参数不足时保留 `?`
- **安全 flush**: `handleContinuation` 遇到终止符时即使参数不足也会处理，防止 SQL 丢失

**常量 Key** (用于 PropertiesComponent 持久化):
- `PREPARING_KEY` — Preparing 前缀
- `PARAMETERS_KEY` — Parameters 前缀
- `KEYWORDS_KEY` — 过滤关键词
- `INSERT_SQL_COLOR_KEY` — INSERT 颜色
- `DELETE_SQL_COLOR_KEY` — DELETE 颜色
- `UPDATE_SQL_COLOR_KEY` — UPDATE 颜色
- `SELECT_SQL_COLOR_KEY` — SELECT 颜色

**NEED_BRACKETS 类型** (参数值需要加单引号):
String, Date, Time, LocalDate, LocalTime, LocalDateTime, BigDecimal, Timestamp

### 4.3 MyBatisLogConsoleFilterProvider

**文件**: `src/main/java/com/starxg/mybatislog/MyBatisLogConsoleFilterProvider.java`

**职责**: 向 IDEA 注册 ConsoleFilterProvider，使 MyBatisLogConsoleFilter 能接收所有控制台输出。通过 `project.getUserData(key)` 单例化。

### 4.4 MyBatisLogToolWindowFactory

**文件**: `src/main/java/com/starxg/mybatislog/gui/MyBatisLogToolWindowFactory.java`

**职责**: ToolWindow 工厂。当用户点击底部 "MyBatis Log Restorer" 图标时，IDEA 调用 `createToolWindowContent`。首次调用时创建 `MyBatisLogManager`，后续调用直接返回（幂等）。

### 4.5 MyBatisLogAction (入口动作)

**文件**: `src/main/java/com/starxg/mybatislog/action/MyBatisLogAction.java`

**职责**: 从两个入口触发：
1. `Tools → MyBatis Log Plugin` 菜单 → `rerun()` 强制重建实例
2. `Console 右键菜单` → 激活已有实例

**`rerun(Project)`**: 调用 `MyBatisLogManager.recreateInstance(project).run()`

### 4.6 CopySqlAction (复制 SQL)

**文件**: `src/main/java/com/starxg/mybatislog/action/CopySqlAction.java`

**职责**: 提供一键复制当前光标所在条目的完整 SQL 语句。

**核心逻辑**:
```
actionPerformed()
  ├── 获取光标行号
  ├── findHeaderLineBackward() → 正则匹配 `-- \d+ --` 找到当前条目头
  ├── findHeaderLineForward()  → 找下一个条目头（或文档结尾）
  └── 提取起点到终点的文本 → 复制到剪贴板
```

**光标位置覆盖**: 光标在条目序号行、SQL 首行、多行 SQL 的任意换行行，均能正确复制完整 SQL。

---

## 5. 插件注册配置 (plugin.xml)

**文件**: `src/main/resources/META-INF/plugin.xml`

### 5.1 Extensions 扩展点

```xml
<extensions defaultExtensionNs="com.intellij">
    <!-- 1. 控制台过滤器提供者: 拦截所有控制台输出 -->
    <consoleFilterProvider implementation="com.starxg.mybatislog.MyBatisLogConsoleFilterProvider"/>

    <!-- 2. 工具窗口: 底部固定, 点击图标打开 -->
    <toolWindow id="MyBatis Log Restorer"
                anchor="bottom"
                icon="/icons/ibatis.svg"
                factoryClass="com.starxg.mybatislog.gui.MyBatisLogToolWindowFactory"/>
</extensions>
```

**`anchor="bottom"`**: 窗口固定在底部面板区域。可选值: `left`, `right`, `bottom`, `top`。

### 5.2 Actions 动作注册

```xml
<actions>
    <action id="com.seekxu.mybatislog.action.MyBatisLogAction"
            class="com.starxg.mybatislog.action.MyBatisLogAction"
            text="MyBatis Log Plugin"
            icon="/icons/ibatis.svg">
        <add-to-group group-id="ToolsMenu" anchor="last"/>
        <add-to-group group-id="ConsoleEditorPopupMenu" anchor="before"
                      relative-to-action="ConsoleView.ClearAll"/>
    </action>
</actions>
```

---

## 6. 构建与部署

### 6.1 环境要求

| 组件 | 要求 |
|---|---|
| **JDK** | Java 17 (通过 IntelliJ IDEA 的 JBR 获取) |
| **Gradle** | 9.6.1 (项目自带 Wrapper) |
| **IntelliJ Platform** | 2024.1+ (sinceBuild=241) |

### 6.2 构建命令

```powershell
# 设置 JAVA_HOME 为 IDEA 自带的 JBR (JDK 17+)
$env:JAVA_HOME = "D:\Program Files\JetBrains\IntelliJ IDEA 2026.2.0.1\jbr"

# 清理并构建 (跳过测试)
.\gradlew.bat clean build -x test

# 运行测试
.\gradlew.bat test
```

### 6.3 部署

1. 构建产物位于 `build/libs/mybatis-log-plugin-free-2.0.0.jar`
2. IDEA 中安装: **File → Settings → Plugins → ⚙ → Install Plugin from Disk...**
3. 重启 IDEA

### 6.4 调试 (开发模式)

在 `build.gradle` 中可以配置:
```groovy
intellijPlatform {
    localIdeaPath = 'D:/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1'
}
```
或通过 Gradle 属性 `-PlocalIdeaPath=...` 指向本地 IDEA 安装路径，直接在 IDEA 中以插件模式运行。

---

## 7. 架构改造历史 (Executor → ToolWindow)

### 7.1 改造原因

**改造前**: 插件通过 `Executor` + `RunnerLayoutUi` + `ExecutionManager` 模式运行，内容嵌入 IDEA 的 **Run 面板**中。这导致：
- 每次需手动通过 `Tools → MyBatis Log Plugin` 启动
- 与应用运行配置混在一起
- 无法独立定位
- 关闭 Run 面板时插件也会被关闭

**改造后**: 改为标准的 `ToolWindow` 扩展点模式：
- 底部独立图标按钮，固定可见
- 独立面板，不依赖 Run 面板
- 点击图标才打开，按需使用

### 7.2 改动文件清单

| 文件 | 操作 | 说明 |
|---|---|---|
| `plugin.xml` | 修改 | 移除 `<executor>`，添加 `<toolWindow anchor="bottom">` |
| `MyBatisLogExecutor.java` | 删除 | 不再需要 Executor 模式 |
| `MyBatisLogManager.java` | 重写 | 移除 `RunnerLayoutUi`/`ExecutionManager`，改用 `ToolWindow.ContentManager` |
| `MyBatisLogToolWindowFactory.java` | 新建 | ToolWindow 工厂 |
| `MyBatisLogStartupActivity.java` | 新建后删除 | 最初用于自动激活，后来移除（toolWindow 注册已足够） |
| `MyBatisLogAction.java` | 修改 | 移除 `Disposer.dispose` 调用，改用 `recreateInstance` |

### 7.3 关键代码变化

**构造函数移除**:
```java
// 旧代码 (已移除)
RunnerLayoutUi layoutUi = getRunnerLayoutUi();
Content content = layoutUi.createContent(...);
ExecutionManager.getInstance(project).getContentManager().showRunContent(...);
toolWindow.activate(null);  // 强制打开面板 ← 已移除
```

**新代码**:
```java
// 新代码: 直接操作 ToolWindow ContentManager
Content content = ContentFactory.getInstance().createContent(panel, "SQL", false);
toolWindow.getContentManager().removeAllContents(true);
toolWindow.getContentManager().addContent(content);
running = true;  // 自动开始捕获
```

**dispose 方法新增**:
```java
// 防止 rerun 产生重复 Tab
ToolWindow toolWindow = getToolWindow();
if (toolWindow != null) {
    toolWindow.getContentManager().removeContent(content, true);
}
```

---

## 8. 常见修改场景指南

### 8.1 添加新的工具栏按钮

1. 在 `action/` 目录下创建新 Action 类，继承 `AnAction`
2. 在 `MyBatisLogManager.createActionToolbar()` 中添加 `actionGroup.add(new YourAction(manager))`
3. 如果 Action 需要 Manager 引用，通过构造函数注入

示例:
```java
public class ExportAction extends AnAction {
    private final MyBatisLogManager manager;
    public ExportAction(MyBatisLogManager manager) {
        super("Export", "Export SQL logs", AllIcons.Actions.Download);
        this.manager = manager;
    }
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // 导出逻辑
    }
}
```

### 8.2 修改 SQL 解析逻辑

编辑 `MyBatisLogConsoleFilter.java`:

- **修改参数类型判断**: 编辑 `NEED_BRACKETS` 集合（第 41-50 行）
- **修改占位符匹配**: `MARK` 字段（第 32 行，默认 `?`）
- **修改 Preparing/Parameters 前缀**: 通过 Settings 对话框或 `PropertiesComponent` 修改
- **修改 SQL 类型判断颜色**: `INSERT_SQL_COLOR_KEY` 等常量

### 8.3 调整 ToolWindow 位置

编辑 `plugin.xml` 中的 `anchor` 属性:
```xml
<toolWindow id="..." anchor="left" .../>   <!-- 左侧 -->
<toolWindow id="..." anchor="right" .../>  <!-- 右侧 -->
<toolWindow id="..." anchor="top" .../>    <!-- 顶部 -->
<toolWindow id="..." anchor="bottom" .../> <!-- 底部 (当前) -->
```

### 8.4 修改 SQL 颜色

编辑 `MyBatisLogConsoleFilter.applyFilter()` 中的颜色逻辑。颜色值通过 `PropertiesComponent` 持久化，可在 Settings 对话框中修改。

### 8.5 修改格式化行为

编辑 `MyBatisLogManager.isFormat()` 或 `BasicFormatter.java`。格式化通过 `PrettyPrintToggleAction` 开关控制，状态保存在 `PropertiesComponent` 中。

### 8.6 修改/添加关键词过滤

编辑 `MyBatisLogConsoleFilter.applyFilter()` 的关键词匹配逻辑。关键词列表通过 `PropertiesComponent` 的 `KEYWORDS_KEY` 持久化。

---

## 9. 已知遗留问题

### 9.1 SettingsDialogWrapper.form 绑定警告

```
[ant:instrumentIdeaExtensions] Class to bind does not exist:
com.starxg.mybatislog.gui.SettingsDialogWrapper
```

**原因**: `.form` 文件引用了 `SettingsDialogWrapper` 类。需确认该类存在且可被正确绑定。

### 9.2 测试运行失败

`gradlew test` 在测试 JVM 启动时崩溃（exit code 268435466），可能与 IDEA 平台测试沙箱环境相关。单元测试仅涉及静态方法，可跳过：`gradlew build -x test`。

### 9.3 跨版本兼容性说明

插件支持 IntelliJ IDEA 2024.1 ~ 2026.2。主要 API 兼容点：
- `ToolWindowManagerListener.stateChanged` - 已移除该监听，改为直接操作
- `ContentFactory.createContent` - 稳定 API，无版本差异
- `ToolWindowFactory` - 稳定接口

### 9.4 DonateAction 版本号机制

捐赠按钮状态通过 `PluginManagerCore.getPlugin(PluginId.getId("com.seekxu.mybatis-log-plugin-free"))` 动态读取版本号。版本更新时，key 变化会导致捐赠按钮重新显示。

**注意**: 升级插件版本时，只需修改 `build.gradle` 中的 `version` 字段，代码会自动读取新版本号。

### 9.5 边界情况（不影响正常使用）

- **JSON 内部逗号**: 若 MyBatis 输出格式化（pretty print）的多行 JSON，缩进空格可能被误判为分隔符。目前 JSON 通常以紧凑单行输出，风险极低
- **cleanupExpired 弱一致性**: `ConcurrentHashMap` 迭代器可能漏掉新增条目，但清理本身就是非精确操作
- **pendingSqls 无容量上限**: 极端堆积场景，已有 10 秒超时兜底
- **trim 高亮扫描**: 超过 10000 行会全量扫描高亮标记，但触发频率极低
- **`<==` 终止符依赖**: 当 SQL 参数跨多行时，`<==` 结果标记可提前 flush 缓冲；若 MyBatis 配置不输出 `<==`，则依赖新 SQL 的 Preparing/Parameters 或参数累积完成路径

---

## 10. 技术栈详情

| 技术 | 版本/说明 |
|---|---|
| Java | 17 (source/target compatibility) |
| IntelliJ Platform SDK | 2.18.1 |
| IntelliJ IDEA | 2024.1 ~ 2026.2 (sinceBuild=241, untilBuild=262.*) |
| Gradle | 9.6.1 |
| Apache Commons Lang 3 | 字符串工具 (StringUtils) |
| JUnit | 4.13.2 |
| Hibernate BasicFormatterImpl | SQL 格式化 (已内嵌修改) |

---

## 11. 扩展点与插件化接口

本插件通过以下 IntelliJ Platform 扩展点注册：

| 扩展点 | 作用 | 实现类 |
|---|---|---|
| `consoleFilterProvider` | 注册控制台过滤器 | `MyBatisLogConsoleFilterProvider` |
| `toolWindow` | 注册工具窗口 | `MyBatisLogToolWindowFactory` |
| `actions` | 注册菜单项 | `MyBatisLogAction` 等 |