# Reliable Release Checklist

A practical guide to preparing a stable release. The goal is not only to make the build pass, but also to make the result **easy to review**, *easy to reproduce*, and safe to roll back.

Before making changes, review the [release policy](https://example.com/release-policy) and confirm the current version in `gradle.properties`.

## 1. Prepare the release

A release should have a clear owner, a repeatable build, and a short summary of the changes that matter to users.

- **Scope:** Identify the features and fixes included in this version.
- **Quality:** Run automated checks and investigate failures.
  - Check dependency updates and security advisories.
  - Verify that generated artifacts use the expected toolchain.
- **Documentation:** Update the changelog and upgrade notes.

> **Review note:** A passing CI pipeline is necessary, but it does not replace checking the final user-facing experience.

### Acceptance criteria

1. Build and tests succeed from the release commit.
2. Artifacts are signed and can be reproduced.
3. A rollback procedure has been tested.

## 2. Verify the build

Use the exact commit that will be tagged. Avoid rebuilding from a moving branch name.

```bash
./gradlew clean test verifyPlugin
./gradlew buildPlugin
sha256sum build/distributions/*.zip
```

The generated checksum helps reviewers establish artifact identity. **Do not** interpret a matching checksum as proof that the UI looks correct.

| Check | Expected result | Owner |
| --- | --- | --- |
| Unit tests | All pass | Development |
| Integration tests | Real IDE verified | QA |
| Visual review | Approved screenshots | Reviewer |
| Release notes | Published draft | Maintainer |

## 3. Check the user experience

The most useful review covers more than one isolated heading. Open a realistic document and inspect how sections flow into ordinary paragraphs, lists, code blocks, and tables.

- [x] Confirm Heading levels have a clear hierarchy.
- [x] Confirm the original Markdown remains editable.
- [ ] Compare the full document with the platform preview.
- [ ] Confirm font-size changes do not clip text.

### Pay attention to spacing

**Vertical rhythm** should make related content feel grouped. A heading should be visually associated with the paragraph that follows, not with the end of the previous section.

For example, inline `code`, **strong text**, *emphasis*, and [links](https://example.com) should remain readable in one continuous sentence.

---

## 4. Publish and monitor

When approval is complete, create the version tag, publish the signed package, and watch the first production runs.

1. Publish release notes and artifacts.
2. Verify the downloaded package and installation flow.
3. Monitor errors and collect follow-up work.

> Keep the release checklist available after publication so the next maintainer can reproduce the process.

A successful release is one that a new contributor can understand, verify, and maintain.
