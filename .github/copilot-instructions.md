# Project Context
You are an expert developer building an IntelliJ IDEA Plugin that provides a WYSIWYG Markdown editor (like Typora).
The project uses a Hybrid Architecture:
1.  **Backend (IntelliJ Plugin):** Kotlin + IntelliJ Platform SDK + JCEF (Chromium Embedded Framework).
2.  **Frontend (Webview):** Pure TypeScript + Vite + Milkdown (Markdown editor framework). NO React, NO Vue.

# Architectural Rules
-   **Separation of Concerns:** The Frontend ONLY handles UI, text rendering, and Markdown parsing. The Backend ONLY handles IDE integration, Virtual File System (VFS) operations, and file saving/loading.
-   **Communication:** Use `JSQuery` (IntelliJ API) to send/receive messages between Kotlin and the JCEF JavaScript environment.
-   **Performance:** The Frontend must be as lightweight as possible to avoid lagging the IDE.

# Coding Style
-   **Kotlin:** Use standard JetBrains conventions. Use Coroutines for background tasks.
-   **TypeScript:** Use strict typing. Avoid heavy external libraries unless necessary.
-   **Responses:** Provide direct, ready-to-use code snippets. Do not explain basic concepts unless asked.

# Ignore File Rules
-   All specified in `.gitignore` should be followed. Do not include any generated files, build artifacts, or IDE-specific files in the repository.