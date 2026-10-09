# Mermaid usecase diagram

Source: upstream Mermaid 12.1.0 E2E fixture (`mermaid-js/mermaid@mermaid@12.1.0/e2e/platform/dev-diagrams/diagrams/use-case/01-basic-use-cases.mmd`).

```mermaid
usecase-beta
    direction LR
    actor Customer("Customer")
    actor Admin("Administrator")
    Browse("Browse catalog")
    Checkout("Place order")
    Manage[Manage catalog]
    Customer --> Browse
    Customer --> Checkout
    Admin --> Manage
```
