# Mermaid swimlanes diagram

```mermaid
swimlane-beta LR
  subgraph Author
    draft[Draft change]
  end
  subgraph Reviewer
    review[Review change]
    approve[Approve]
  end
  draft --> review --> approve
```
