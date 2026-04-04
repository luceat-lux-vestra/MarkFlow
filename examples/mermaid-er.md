# Mermaid ER Diagram Example

```mermaid
erDiagram
    USER ||--o{ NOTE : writes
    NOTE ||--o{ TAG : has
    USER {
        int id
        string name
    }
    NOTE {
        int id
        string title
        string body
    }
    TAG {
        int id
        string name
    }
```

