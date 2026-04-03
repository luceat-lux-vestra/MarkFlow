# MarkFlow - Seamless WYSIWYG Markdown Editor

![Build](https://github.com/luceat-lux-vestra/MarkFlow/workflows/Build/badge.svg)
[![Version](https://img.shields.io/badge/Marketplace-pending-lightgrey)](https://plugins.jetbrains.com/)

MarkFlow is an IntelliJ IDEA plugin that provides a Typora-style Markdown editing experience using a JCEF webview and Milkdown.

## Features

- WYSIWYG-style Markdown editing in a custom `FileEditor`
- IntelliJ <-> Webview synchronization via `JBCefJSQuery`
- Scroll/cursor/selection state restore between editor sessions
- Frontend build integrated into Gradle plugin tasks

<!-- Plugin description -->
MarkFlow provides a lightweight WYSIWYG Markdown editor for IntelliJ-based IDEs.
It uses a hybrid architecture: Kotlin + IntelliJ Platform on the backend, and TypeScript + Milkdown in a JCEF webview on the frontend.
The editor focuses on fast rendering, accurate Markdown synchronization, and IDE-native file integration.
<!-- Plugin description end -->

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
