# Mermaid wardley diagram

Upstream fixture: mermaid-js/mermaid, mermaid@12.1.0, e2e/diagrams/wardley/5-should-render-custom-canvas-size.mmd

```mermaid
wardley-beta
  title Custom Size Map
  size [600, 800]

  anchor User [0.95, 0.90]
  component App [0.75, 0.85]
  component API [0.50, 0.70]
  component Database [0.30, 0.50]
  component Cloud [0.15, 0.30]

  User -> App
  App -> API
  API -> Database
  Database -> Cloud

  evolve Database 0.60
```
