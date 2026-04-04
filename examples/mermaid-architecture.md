# Mermaid Architecture Example

```mermaid
architecture-beta
    group ide(cloud)[IDE]
    service web(server)[Webview] in ide
    service plugin(database)[Plugin Core] in ide
    service vfs(disk)[VFS] in ide

    web:R -- L:plugin
    plugin:B -- T:vfs
```

