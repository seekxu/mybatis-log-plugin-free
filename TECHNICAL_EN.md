# MyBatis Log Plugin Free — Technical Documentation

---

## 1. Project Overview

| Attribute | Value |
|---|---|
| **Project Name** | MyBatis Log Free |
| **Type** | IntelliJ IDEA Platform Plugin |
| **Plugin ID** | `com.seekxu.mybatis-log-plugin-free` |
| **Version** | 2.0.0 |
| **Author** | seekxu (Forked from starxg) |
| **License** | GPL v2 |
| **Target Platform** | IntelliJ IDEA 2024.1 ~ 2026.2 (sinceBuild=241, untilBuild=262.*) |
| **Language/Framework** | Java 17, IntelliJ Platform SDK 2.18.1 |
| **Build Tool** | Gradle 9.6.1 |

### 1.1 Core Function

Restores MyBatis two-line SQL logs:
```
Preparing: UPDATE mp_user SET name=? WHERE id=?
Parameters: 张三(String), 1(Long)
```
Into complete executable SQL:
```
UPDATE mp_user SET name='张三' WHERE id=1
```
Displayed in an independent tool window at IDEA's bottom panel, with syntax highlighting, formatting, and navigation.

### 1.2 Key Features

- **SQL Log Restoration** — Replace `?` placeholders with actual parameter values
- **Independent ToolWindow** — Fixed at bottom, click icon to open
- **Syntax Highlighting** — Different colors for INSERT/DELETE/UPDATE/SELECT/WITH/EXPLAIN
- **SQL Formatting** — Built-in Hibernate BasicFormatter, toggle on/off
- **SQL Navigation** — Previous/Next SQL jump with range highlighters
- **Copy SQL** — One-click copy current SQL entry (regex-based, cursor anywhere)
- **Keyword Filtering** — Ignore irrelevant logs by keyword
- **Prefix Customization** — Custom `Preparing:` / `Parameters:` prefixes
- **Multi-thread Concurrency** — Correct pairing of interleaved multi-thread logs
- **Log Format Compatibility** — 3-tier source extraction (logger → [thread] → full prefix)
- **Auto-cleanup** — 10s timeout for unmatched Preparing, console auto-trim at 10000 lines

---

## 2. Architecture

### 2.1 Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                      IntelliJ IDEA                          │
│                                                              │
│  ┌───────────────────┐     ┌─────────────────────────────┐  │
│  │   Console Output   │────▶│  MyBatisLogConsoleFilter   │  │
│  │   (IDEA Console)   │     │  (Intercept + Parse)       │  │
│  └───────────────────┘     └──────────────┬──────────────┘  │
│                                           │                  │
│                                           ▼                  │
│                                 ┌──────────────────────┐    │
│                                 │   MyBatisLogManager  │    │
│                                 │  (Singleton / State)  │    │
│                                 └──────────┬───────────┘    │
│                                            │                 │
│                                            ▼                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │           ToolWindow "MyBatis Log Plugin Free"       │    │
│  │  ┌────────────────────────────────────────────────┐  │    │
│  │  │  ActionToolbar  │  ConsoleView (SQL Display)   │  │    │
│  │  │  ┌────────────┐│  ┌────────────────────────┐  │  │    │
│  │  │  │ Rerun      ││  │ -- 1 -- UPDATE ...     │  │  │    │
│  │  │  │ Stop       ││  │ UPDATE ... WHERE ...   │  │  │    │
│  │  │  │ Settings   ││  │ -- 2 -- INSERT ...     │  │  │    │
│  │  │  │ Prev/Next  ││  │ INSERT INTO ...        │  │  │    │
│  │  │  │ Copy       ││  │ ...                    │  │  │    │
│  │  │  │ Format     ││  │                        │  │  │    │
│  │  │  │ Clear      ││  │                        │  │  │    │
│  │  │  └────────────┘│  └────────────────────────┘  │  │    │
│  │  └────────────────────────────────────────────────┘  │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Data Flow

```
MyBatis Log Output
    │
    ▼
IDEA Console Output Stream
    │
    ▼
MyBatisLogConsoleFilter.applyFilter(line, length)
    │
    ├── Check MyBatisLogManager.isRunning()
    ├── Keyword filtering
    ├── Match "Preparing:" → cache SQL template
    ├── Match "Parameters:" → trigger parsing
    │
    ▼
MyBatisLogConsoleFilter.parseParams(line)
    │  Parse "张三(String), 1(Long)" → Queue<Entry<value, type>>
    ▼
MyBatisLogConsoleFilter.parseSql(sql, params)
    │  Replace ? placeholders with parameters
    │  String/Date types add single quotes
    ▼
MyBatisLogManager.println(prefix, sql, rgb)
    │
    ├── Determine color by SQL type (INSERT/DELETE/UPDATE/SELECT)
    ├── Optional formatting (BasicFormatter)
    └── Output to ConsoleView
```

### 2.3 Class Relationships

```
MyBatisLogConsoleFilterProvider ──creates──▶ MyBatisLogConsoleFilter
        │                                        │
        │  Registered as ConsoleFilterProvider   │  reads
        │                                        ▼
        │                                MyBatisLogManager (Singleton)
        │                                        │
        │                                        ├── ConsoleViewImpl (SQL display)
        │                                        ├── ActionToolbar (left toolbar)
        │                                        ├── ToolWindow (bottom panel)
        │                                        └── Disposable (lifecycle)
        │
MyBatisLogToolWindowFactory ──creates──▶ MyBatisLogManager (first open)
        │
MyBatisLogAction ──rerun/activate──▶ MyBatisLogManager.recreateInstance()
        │
Action classes (RerunAction, StopAction, SettingsAction, CopySqlAction, ...)
        │  Injected with MyBatisLogManager reference
        └── Operate MyBatisLogManager's run/stop/settings
```

---

## 3. Directory Structure

```
mybatis-log-plugin-free/
├── src/
│   ├── main/
│   │   ├── java/com/starxg/mybatislog/
│   │   │   ├── MyBatisLogConsoleFilter.java        # Core: console filter + SQL parser
│   │   │   ├── MyBatisLogConsoleFilterProvider.java # Filter provider, registered to IDEA
│   │   │   ├── BasicFormatter.java                 # SQL formatter (from Hibernate)
│   │   │   ├── Icons.java                          # Icon constants
│   │   │   ├── gui/
│   │   │   │   ├── MyBatisLogManager.java          # Core manager (singleton, UI, state)
│   │   │   │   ├── MyBatisLogToolWindowFactory.java # ToolWindow factory
│   │   │   │   ├── SettingsDialogWrapper.java      # Settings dialog
│   │   │   │   └── DonateDialogWrapper.java        # Donation dialog
│   │   │   └── action/
│   │   │       ├── MyBatisLogAction.java           # Entry action (Tools + right-click)
│   │   │       ├── RerunAction.java                # Restart plugin
│   │   │       ├── StopAction.java                 # Stop listening
│   │   │       ├── SettingsAction.java             # Open settings
│   │   │       ├── PreviousSqlAction.java          # Previous SQL
│   │   │       ├── NextSqlAction.java              # Next SQL
│   │   │       ├── PrettyPrintToggleAction.java    # Format toggle
│   │   │       ├── ClearAllAction.java             # Clear console
│   │   │       ├── DonateAction.java               # Donation
│   │   │       ├── JumpSqlAction.java              # SQL navigation abstract base
│   │   │       └── CopySqlAction.java             # Copy current SQL entry
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   ├── plugin.xml                      # Plugin descriptor
│   │       │   └── pluginIcon.svg                  # Plugin icon
│   │       ├── icons/
│   │       │   ├── ibatis.svg                      # MyBatis icon
│   │       │   ├── coffee.svg                      # Donation icon
│   │       │   └── prettyPrint.svg                 # Format icon
│   │       └── images/
│   │           ├── alipay.png                      # Alipay QR code
│   │           └── wechatpay.png                   # WeChat Pay QR code
│   └── test/
│       └── java/com/starxg/mybatislog/
│           └── MyBatisLogConsoleFilterTest.java    # Unit tests
├── build.gradle                                     # Gradle build script
├── settings.gradle
├── gradlew / gradlew.bat
├── README.md / README_EN.md
└── TECHNICAL.md / TECHNICAL_EN.md
```

---

## 4. Core Class Details

### 4.1 MyBatisLogManager (Core Manager)

**File**: `src/main/java/com/starxg/mybatislog/gui/MyBatisLogManager.java`

**Responsibilities**: Singleton managing plugin runtime state and UI.

**Key Methods**:

| Method | Description |
|---|---|
| `createInstance(Project)` | Create instance (return existing if valid) |
| `recreateInstance(Project)` | Force destroy and recreate (for Rerun) |
| `getInstance(Project)` | Get current instance (null if ToolWindow unavailable) |
| `run()` | Start listening for MyBatis logs |
| `stop()` | Stop listening |
| `resetCounter()` | Reset SQL sequence counter (on clear) |
| `println(logPrefix, sql, rgb)` | Output restored SQL to ConsoleView; auto-trims oldest 20% when >10000 lines |
| `dispose()` | Cleanup resources (remove content, stop listening) |

**Design Decisions**:
- `createInstance` is idempotent; `recreateInstance` forces rebuild
- `dispose()` must call `removeContent` to prevent duplicate tabs on Rerun
- Constructor calls `removeAllContents(true)` to ensure no residual content
- `running = true` at end of constructor: auto-starts capture on panel open

---

### 4.2 MyBatisLogConsoleFilter (Log Filter)

**File**: `src/main/java/com/starxg/mybatislog/MyBatisLogConsoleFilter.java`

**Responsibilities**: Intercept IDEA console output, parse and restore MyBatis SQL.

**Core Parsing Logic**:
```
applyFilter(line)
  ├── Extract source (3-tier: logger regex → [thread] → full prefix)
  ├── Match "Preparing:" → cache pendingSqls[source]
  ├── Match "Parameters:" → match by source, pair, call manager.println()
  ├── Cleanup every 5s (expired entries >10s)
  └── Other lines: ignore

parseSql(sql, params)
  ├── Iterate '?' placeholders in SQL
  ├── Take corresponding parameter from params queue
  ├── String/Date types add single quotes: 'value'
  └── Numeric types replace directly: value

parseParams(line)
  ├── Split "1(Long), 张三(String)"
  └── Return Queue<Entry<value, type>>
```

**Key Design**:
- **Map multi-source cache**: `ConcurrentHashMap<String, PendingSql>` for multi-thread concurrency
- **10s timeout**: Auto-cleanup of expired Preparing entries
- **ThreadLocal Matcher**: Avoid creating regex matchers per log line
- **CopyOnWriteArrayList** for thread-safe keyword filtering

---

### 4.3 MyBatisLogConsoleFilterProvider

**File**: `src/main/java/com/starxg/mybatislog/MyBatisLogConsoleFilterProvider.java`

Registers `ConsoleFilterProvider` with IDEA, enabling `MyBatisLogConsoleFilter` to receive all console output. Singleton via `project.getUserData(key)`.

### 4.4 MyBatisLogToolWindowFactory

**File**: `src/main/java/com/starxg/mybatislog/gui/MyBatisLogToolWindowFactory.java`

ToolWindow factory. When user clicks "MyBatis Log Free" icon, IDEA calls `createToolWindowContent`. First call creates `MyBatisLogManager`, subsequent calls return (idempotent).

### 4.5 MyBatisLogAction (Entry Action)

**File**: `src/main/java/com/starxg/mybatislog/action/MyBatisLogAction.java`

Two entry points:
1. `Tools → MyBatis Log Plugin` menu → `rerun()` force rebuild
2. `Console right-click menu` → activate existing instance

**`rerun(Project)`**: Calls `MyBatisLogManager.recreateInstance(project).run()`

### 4.6 CopySqlAction (Copy SQL)

**File**: `src/main/java/com/starxg/mybatislog/action/CopySqlAction.java`

**Responsibilities**: One-click copy of the current SQL entry.

**Core Logic**:
```
actionPerformed()
  ├── Get caret line number
  ├── findHeaderLineBackward() → regex match `-- \d+ --` to find entry header
  ├── findHeaderLineForward()  → find next header (or document end)
  └── Extract text → copy to clipboard
```

**Cursor Position Coverage**: Works when cursor is on the header line, first SQL line, or any line of multi-line SQL.

---

## 5. Plugin Registration (plugin.xml)

**File**: `src/main/resources/META-INF/plugin.xml`

### 5.1 Extensions

```xml
<extensions defaultExtensionNs="com.intellij">
    <!-- 1. Console filter provider: intercept all console output -->
    <consoleFilterProvider implementation="com.starxg.mybatislog.MyBatisLogConsoleFilterProvider"/>

    <!-- 2. Tool window: fixed at bottom, click icon to open -->
    <toolWindow id="MyBatis Log Plugin Free"
                anchor="bottom"
                icon="/icons/ibatis.svg"
                factoryClass="com.starxg.mybatislog.gui.MyBatisLogToolWindowFactory"/>
</extensions>
```

### 5.2 Actions

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

## 6. Build & Deploy

### 6.1 Requirements

| Component | Requirement |
|---|---|
| **JDK** | Java 17 (via IDEA's bundled JBR) |
| **Gradle** | 9.6.1 (Wrapper included) |
| **IntelliJ Platform** | 2024.1+ (sinceBuild=241) |

### 6.2 Build Command

```powershell
# Set JAVA_HOME to IDEA's bundled JBR
$env:JAVA_HOME = "D:\Program Files\JetBrains\IntelliJ IDEA 2026.2.0.1\jbr"

# Clean build (skip tests)
.\gradlew.bat clean build -x test

# Run tests
.\gradlew.bat test
```

### 6.3 Deploy

1. Artifact at `build/libs/mybatis-log-plugin-free-2.0.0.jar`
2. IDE installation: **File → Settings → Plugins → ⚙ → Install Plugin from Disk...**
3. Restart IDEA

---

## 7. Architecture Migration (Executor → ToolWindow)

**Before**: Plugin used `Executor` + `RunnerLayoutUi` + `ExecutionManager`, embedded in IDEA's Run panel. Required manual startup via `Tools → MyBatis Log Plugin`, mixed with run configurations, and closed when Run panel was closed.

**After**: Standard `ToolWindow` extension point:
- Independent bottom icon, always visible
- Separate panel, independent of Run panel
- Click icon to open on demand

**Modified Files**:

| File | Action | Description |
|---|---|---|
| `plugin.xml` | Modified | Removed `<executor>`, added `<toolWindow anchor="bottom">` |
| `MyBatisLogExecutor.java` | Deleted | Executor mode no longer needed |
| `MyBatisLogManager.java` | Rewritten | Removed `RunnerLayoutUi`/`ExecutionManager`, uses `ToolWindow.ContentManager` |
| `MyBatisLogToolWindowFactory.java` | New | ToolWindow factory |
| `MyBatisLogAction.java` | Modified | Removed `Disposer.dispose`, uses `recreateInstance` |

---

## 8. Known Issues

### 8.1 SettingsDialogWrapper.form Binding Warning
```
[ant:instrumentIdeaExtensions] Class to bind does not exist:
com.starxg.mybatislog.gui.SettingsDialogWrapper
```
The `.form` file references the `SettingsDialogWrapper` class. Does not affect functionality.

### 8.2 Test JVM Crash
`gradlew test` crashes with exit code 268435466, possibly related to IDEA platform test sandbox. Unit tests only cover static methods. Workaround: `gradlew build -x test`.

### 8.3 Version Compatibility
Supports IDEA 2024.1 ~ 2026.2. Key API compatibility:
- `ContentFactory.createContent` — stable API
- `ToolWindowFactory` — stable interface

### 8.4 Edge Cases (Non-critical)

- **parseParams comma split**: Parameter values containing `, ` will be incorrectly split, though MyBatis never outputs such parameters
- **cleanupExpired weak consistency**: `ConcurrentHashMap` iterator may miss new entries during cleanup, but cleanup is inherently non-precise
- **pendingSqls no capacity limit**: 10s timeout is sufficient as safety net
- **Trim highlight scanning**: Full scan of highlights when exceeding 10000 lines, but triggered rarely

---

## 9. Technology Stack

| Technology | Version/Details |
|---|---|
| Java | 17 (source/target) |
| IntelliJ Platform SDK | 2.18.1 |
| IntelliJ IDEA | 2024.1 ~ 2026.2 |
| Gradle | 9.6.1 |
| Apache Commons Lang 3 | StringUtils |
| JUnit | 4.13.2 |
| Hibernate BasicFormatterImpl | SQL formatting (embedded, modified) |