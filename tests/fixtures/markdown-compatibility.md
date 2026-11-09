# Shared Markdown compatibility fixture

This paragraph checks **strong text**, *emphasis*, ~~strikethrough~~, `inline code`, and a [link](https://example.org/docs). A plain URL https://example.org/plain-url should linkify too. A literal newline
continues as a soft break.

- unordered item one
- [x] completed task
- [ ] pending task
  - nested unordered child

1. ordered item one
2. ordered item two

> A blockquote stays visible on both renderers.
>
> It may contain **formatted text**.

| Syntax | Expected |
| --- | --- |
| table | cells |
| GFM | visible |

```kotlin
fun fixture() = "fenced code"
```

Text before a missing image ![missing test image](file:///myvault-fixture-does-not-exist/missing.png) and text after it must remain visible.

## Long-content section

This sentence is repeated to exercise scrolling in the reader. This sentence is repeated to exercise scrolling in the reader. This sentence is repeated to exercise scrolling in the reader. This sentence is repeated to exercise scrolling in the reader.
