# Large Mermaid Flowchart Example

This is a comprehensive example demonstrating a large and wide flowchart that benefits from the different diagram size modes.

## Software Development Workflow

```mermaid
flowchart LR
    A["📋 Project Initiation"] --> B["👥 Team Meeting"]
    B --> C["📝 Requirement Gathering"]
    C --> D["✓ Requirements Approved"]
    D --> E["🎨 UI/UX Design"]
    E --> F["💻 Frontend Development"]
    E --> G["🔧 Backend API Design"]

    F --> H["🧪 Frontend Unit Tests"]
    G --> I["📊 Database Schema"]
    I --> J["🔌 API Implementation"]
    J --> K["✓ API Code Review"]
    K --> L["🧪 Backend Unit Tests"]

    H --> M["🔗 Integration Phase"]
    L --> M

    M --> N["🐛 Integration Testing"]
    N --> O{Bugs Found?}
    O -->|Yes| P["🔧 Bug Fix"]
    P --> N
    O -->|No| Q["✓ Integration Approved"]

    Q --> R["📱 QA Testing"]
    R --> S["📋 Test Report"]
    S --> T{Pass?}
    T -->|No| U["📝 Issue Log"]
    U --> P
    T -->|Yes| V["🚀 Release Candidate"]

    V --> W["⚙️ Staging Deploy"]
    W --> X["🔍 Staging Verification"]
    X --> Y{Ready?}
    Y -->|No| Z["🔧 Last Minute Fixes"]
    Z --> W
    Y -->|Yes| AA["✅ Production Deploy"]

    AA --> AB["📊 Monitoring"]
    AB --> AC{Issues?}
    AC -->|Critical| AD["🚨 Hotfix"]
    AD --> AE["🔄 Redeploy"]
    AE --> AB
    AC -->|No| AF["📈 Release Success"]

    AF --> AG["📚 Documentation"]
    AG --> AH["🎉 Release Complete"]

    style A fill:#e1f5ff
    style D fill:#c8e6c9
    style Q fill:#c8e6c9
    style V fill:#fff9c4
    style AA fill:#ffe0b2
    style AF fill:#c8e6c9
    style AH fill:#a5d6a7
    style O fill:#ffccbc
    style T fill:#ffccbc
    style Y fill:#ffccbc
    style AC fill:#ffccbc
```

---

## Complex Business Process

```mermaid
flowchart TD
    Start["🏢 Customer Request"] --> Check1{Request Type}

    Check1 -->|Sales Inquiry| Sales["💼 Sales Department"]
    Check1 -->|Technical Support| Support["🔧 Support Department"]
    Check1 -->|Billing Issue| Billing["💰 Billing Department"]

    Sales --> SalesQ["📞 Initial Consultation"]
    SalesQ --> SalesCheck{Qualified Lead?}
    SalesCheck -->|No| Reject1["❌ Send Decline Email"]
    SalesCheck -->|Yes| Quote["📄 Generate Quote"]

    Support --> SupportQ["🔍 Issue Assessment"]
    SupportQ --> SupportCheck{Can resolve remotely?}
    SupportCheck -->|Yes| Remote["🖥️ Remote Support"]
    SupportCheck -->|No| Onsite["🚗 Schedule Onsite Visit"]

    Billing --> BillingQ["💳 Review Account"]
    BillingQ --> BillingCheck{Billing Dispute?}
    BillingCheck -->|Yes| Dispute["⚖️ Dispute Resolution"]
    BillingCheck -->|No| Invoice["📋 Generate Invoice"]

    Quote --> Partner["🤝 Internal Review"]
    Partner --> Approve1{Approved?}
    Approve1 -->|No| Revise["✏️ Revise Quote"]
    Revise --> Partner
    Approve1 -->|Yes| CreateOrder["📦 Create Order"]

    Remote --> RemoteRes{Resolved?}
    RemoteRes -->|Yes| Close1["✅ Close Ticket"]
    RemoteRes -->|No| Onsite

    Onsite --> Schedule["📅 Schedule Visit"]
    Schedule --> OnsiteWork["👨‍🔧 Perform Work"]
    OnsiteWork --> OnsiteRes{Resolved?}
    OnsiteRes -->|No| Escalate["⬆️ Escalate to Senior"]
    Escalate --> OnsiteWork
    OnsiteRes -->|Yes| Close1

    Dispute --> Review["🔎 Detailed Review"]
    Review --> Adjust{Adjustment Needed?}
    Adjust -->|Yes| Credit["💳 Apply Credit"]
    Adjust -->|No| Maintain["➡️ Maintain Charge"]

    Invoice --> Maintain
    Credit --> Close2["✅ Resolve Issue"]

    CreateOrder --> Fulfillment["📦 Order Fulfillment"]
    Fulfillment --> Shipping["🚚 Arrange Shipping"]
    Shipping --> Transit["🚛 In Transit"]
    Transit --> Delivery["📍 Delivered"]
    Delivery --> Satisfaction["😊 Customer Satisfaction"]

    Close1 --> End1["🏁 Ticket Closed"]
    Close2 --> End2["🏁 Issue Resolved"]
    Satisfaction --> End3["🏁 Order Complete"]
    Reject1 --> End4["🏁 Request Declined"]

    style Start fill:#bbdefb
    style Sales fill:#f3e5f5
    style Support fill:#e8f5e9
    style Billing fill:#fff3e0
    style Quote fill:#f3e5f5
    style Remote fill:#e8f5e9
    style Dispute fill:#fff3e0
    style CreateOrder fill:#f3e5f5
    style Fulfillment fill:#f3e5f5
    style Delivery fill:#f3e5f5
    style Close1 fill:#c8e6c9
    style Close2 fill:#c8e6c9
    style End1 fill:#a5d6a7
    style End2 fill:#a5d6a7
    style End3 fill:#a5d6a7
    style End4 fill:#ef9a9a
```

---

## Use This Example To Test Diagram Sizes

1. **FIT_TO_VIEWPORT**: The entire diagram is scaled to fit within your viewport width
2. **SHRINK_TO_FIT**: If the diagram is wider than your viewport, it shrinks to fit; otherwise displays at actual size
3. **ACTUAL_SIZE_SCROLL**: Displays at actual rendered size with scrollbars when needed

Try switching between different size modes in the MarkFlow settings to see how the diagram rendering changes!

---

## Tips for Using Large Diagrams

- **For FIT_TO_VIEWPORT**: Best for getting an overview of the entire workflow
- **For SHRINK_TO_FIT**: Good balance between detail and fitting in the viewport
- **For ACTUAL_SIZE_SCROLL**: Best when you need to see details and don't mind scrolling around

