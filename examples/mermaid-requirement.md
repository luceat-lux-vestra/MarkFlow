# Mermaid Requirement Diagram Example

```mermaid
requirementDiagram
    requirement req_render {
        id: REQ-001
        text: The editor shall render Mermaid diagrams
        risk: medium
        verifymethod: test
    }

    requirement req_size_mode {
        id: REQ-002
        text: The editor shall support multiple size modes
        risk: low
        verifymethod: analysis
    }

    element markflow_preview {
        type: component
    }

    markflow_preview - satisfies -> req_render
    markflow_preview - satisfies -> req_size_mode
```

