# Contributing

Issues and pull requests are welcome. Keep changes focused, explain the user-visible
behavior, and include a deterministic regression test for changed behavior.

Before opening a pull request:

```bash
mvn -B -ntp clean test
mvn -B -ntp package
```

Tests that use a real browser, an external site, or a network are opt-in. Do not
include cookies, credentials, personal data, profile directories, screenshots,
private URLs, or generated archives in issues, fixtures, commits, or pull requests.
Use `example.com` and synthetic values instead.

Use conventional commit prefixes such as `feat:`, `fix:`, `test:`, and `docs:`.
Update the README when public APIs or runtime requirements change.
