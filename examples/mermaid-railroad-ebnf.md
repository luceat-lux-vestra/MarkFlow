# Mermaid railroad-ebnf diagram

Fixture copied from Mermaid 12.1.0's `packages/examples/src/examples/railroad-ebnf.ts` default example.

```mermaid
railroad-ebnf-beta
    title Expression Grammar

    expression = term ( "+" term | "-" term )* ;
    term = factor ( "*" factor | "/" factor )* ;
    factor = number | "(" expression ")" ;
    number = digit+ ;
    digit = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" ;
```
