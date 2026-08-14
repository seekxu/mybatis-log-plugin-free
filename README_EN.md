# MyBatis Log Restorer

[**中文**](README.md) | [**English**](README_EN.md)

> Forked from [starxg/mybatis-log-plugin-free](https://github.com/starxg/mybatis-log-plugin-free), refactored with independent ToolWindow architecture. Supports IDEA 2024.1 - 2026.2.

An IntelliJ IDEA plugin that intercepts MyBatis `Preparing` and `Parameters` console logs and reconstructs them into complete executable SQL statements. Features multi-thread concurrency support, XML/JSON multi-line parameter handling, broad log format compatibility, and a standalone bottom ToolWindow.

## Features

- **SQL Log Restoration** — Merges `Preparing:` and `Parameters:` logs, replaces placeholders to restore complete SQL
- **Independent ToolWindow** — Fixed at bottom, click icon to open
- **Syntax Highlighting** — Different colors for INSERT/DELETE/UPDATE/SELECT/WITH/EXPLAIN
- **SQL Formatting** — Toggle auto-indentation formatting
- **SQL Navigation** — Previous/Next SQL navigation
- **Copy SQL** — One-click copy of the current SQL entry (cursor anywhere within the entry)
- **Keyword Filtering** — Ignore irrelevant logs by keywords
- **Prefix Customization** — Supports custom `Preparing:` / `Parameters:` prefixes for different log frameworks
- **Multi-thread Concurrency** — Supports interleaved multi-thread logs, correctly pairs by logger source
- **Log Format Compatibility** — Adapts to various custom log formats (3-tier source extraction)
- **Auto-cleanup** — 10-second timeout for unmatched Preparing, prevents memory leaks

## Compatibility

Supports IntelliJ IDEA **2024.1 ~ 2026.2**.

## Installation

This plugin is **NOT published on JetBrains Marketplace**. Download the built `.jar` from GitHub Releases and install manually:

1. Go to [Releases](https://github.com/seekxu/mybatis-log-plugin-free/releases) and download the latest `.jar`
2. Open IDEA: **File → Settings → Plugins → ⚙ → Install Plugin from Disk...**
3. Select the downloaded `.jar`, restart IDEA

## Usage

After installation, the **MyBatis Log Restorer** icon will appear in IDEA's bottom toolbar. Click the icon to open the panel, and the plugin will automatically start capturing MyBatis SQL logs.

## Build

```powershell
# Set JAVA_HOME to IDEA's bundled JBR
$env:JAVA_HOME = "D:\Program Files\JetBrains\IntelliJ IDEA 2026.2.0.1\jbr"

# Build (skip tests)
.\gradlew.bat clean build -x test
```

## Technical Documentation

See [TECHNICAL.md](TECHNICAL.md) (Chinese) or [TECHNICAL_EN.md](TECHNICAL_EN.md) (English).

## License

Copyright (C) 2026 seekxu

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

See the [LICENSE](LICENSE) file for details.

## Donate

If you find this plugin useful, you can buy me a coffee ☕

### WeChat / Alipay

<div align="left">
  <img src="src/main/resources/images/wechatpay.png" alt="WeChat Pay" width="180"/>
  <img src="src/main/resources/images/alipay.png" alt="Alipay" width="180"/>
</div>

### Ko-fi

<div align="left">
  <a href="https://ko-fi.com/seekxu" target="_blank">
    <img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support me on Ko-fi"/>
  </a>
  <a href="https://ko-fi.com/seekxu" target="_blank">Support on Ko-fi</a>
</div>