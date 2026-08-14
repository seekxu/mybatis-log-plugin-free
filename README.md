# MyBatis Log Restorer

[**中文**](README.md) | [**English**](README_EN.md)

> 基于 [starxg/mybatis-log-plugin-free](https://github.com/starxg/mybatis-log-plugin-free) 重构，采用独立 ToolWindow 架构，支持 IDEA 2024.1 - 2026.2。

一个 IntelliJ IDEA 插件，拦截 MyBatis 框架输出的 `Preparing` 和 `Parameters` 日志，还原为完整的可执行 SQL 语句。支持多线程日志交错、XML/JSON 大参数续行、多种日志格式适配，底部独立面板展示。

## 功能

- **SQL 日志还原** — 将 `Preparing:` 和 `Parameters:` 两行日志合并，替换占位符还原为完整 SQL
- **独立 ToolWindow** — 固定在底部，点击图标即可打开面板查看
- **语法高亮** — INSERT/DELETE/UPDATE/SELECT 不同颜色显示
- **SQL 格式化** — 可切换自动缩进格式化
- **SQL 导航** — 上一条/下一条跳转
- **复制 SQL** — 一键复制光标所在条目的完整 SQL 语句
- **关键词过滤** — 按关键词忽略不相关日志
- **前缀自定义** — 支持修改 `Preparing:` / `Parameters:` 前缀适配不同日志框架
- **多线程并发** — 支持多线程日志交错，按来源正确配对
- **日志格式兼容** — 适配多种自定义日志格式

## 兼容性

支持 IntelliJ IDEA **2024.1 ~ 2026.2**。

## 安装

本插件 **未发布到 JetBrains Marketplace**。请从 GitHub Releases 下载构建产物后手动安装：

1. 前往 [Releases](https://github.com/seekxu/mybatis-log-plugin-free/releases) 下载最新版本的 `.jar` 文件
2. 打开 IDEA: **File → Settings → Plugins → ⚙ → Install Plugin from Disk...**
3. 选择下载的 `.jar` 文件，重启 IDEA 即可

## 使用

安装后，IDEA 底部工具栏会出现 **MyBatis Log Restorer** 图标。点击图标打开面板，插件会自动开始捕获 MyBatis SQL 日志。

## 构建

```powershell
# 设置 JAVA_HOME 为 IDEA 自带的 JBR
$env:JAVA_HOME = "D:\Program Files\JetBrains\IntelliJ IDEA 2026.2.0.1\jbr"

# 构建 (跳过测试)
.\gradlew.bat clean build -x test
```

## 技术文档

详见 [TECHNICAL.md](TECHNICAL.md)（中文）或 [TECHNICAL_EN.md](TECHNICAL_EN.md)（英文）。

## 许可证

Copyright (C) 2026 seekxu

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

See the [LICENSE](LICENSE) file for details.

## 捐赠

如果你觉得这个插件有用，可以请作者喝杯咖啡 ☕

### 微信 / 支付宝

<div align="left">
  <img src="src/main/resources/images/wechatpay.png" alt="微信收款" width="180"/>
  <img src="src/main/resources/images/alipay.png" alt="支付宝收款" width="180"/>
</div>

### Ko-fi

<div align="left">
  <a href="https://ko-fi.com/seekxu" target="_blank">
    <img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support me on Ko-fi"/>
  </a>
  <a href="https://ko-fi.com/seekxu" target="_blank">Support on Ko-fi</a>
</div>