# Mermaid agentflow diagram

```mermaid
agentflow-beta TB
  flow reviewer["Review Agent"]
    input["Pull request"]@{ shape: input }
    inspect["Inspect changes"]@{ shape: task }
    check["Run checks"]@{ shape: tool }
    input --> inspect --> check
  end
```
