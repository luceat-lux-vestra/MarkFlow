# Mermaid railroad-abnf diagram

Fixture copied from Mermaid 12.1.0's `packages/examples/src/examples/railroad-abnf.ts` default example.

```mermaid
railroad-abnf-beta
    title Email Address

    address = local-part "@" domain ;
    local-part = 1*( ALPHA / DIGIT / "." / "-" ) ;
    domain = label *( "." label ) ;
    label = 1*( ALPHA / DIGIT / "-" ) ;
```
