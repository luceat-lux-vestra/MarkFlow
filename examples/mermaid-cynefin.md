# Mermaid cynefin diagram

Source: upstream Mermaid 12.1.0 E2E fixture (`mermaid-js/mermaid@mermaid@12.1.0/e2e/diagrams/cynefin/should-render-a-simple-cynefin-diagram-with-all-five-domains.mmd`).

```mermaid
cynefin-beta
  title Incident Response

  complex
  "Investigate root cause"
  "Run chaos experiment"

  complicated
  "Analyze performance data"
  "Expert review needed"

  clear
  "Restart service"
  "Apply known fix"

  chaotic
  "Page on-call immediately"

  confusion
  "Unknown failure mode"
```
