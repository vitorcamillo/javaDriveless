# Java Driverless

Java browser automation over Chrome DevTools Protocol (CDP), without ChromeDriver.
The library manages Chrome profiles, navigation, input, cookies, JavaScript, and
multiple targets through a Java API.

This public edition contains no browser profiles, cookies, credentials, proxy
configuration, or private project data.

## Requirements

- JDK 21 or newer
- Maven 3.9 or newer
- Google Chrome or Chromium with a desktop display for browser tests

The default test suite is deterministic and excludes tests tagged `browser`,
`external`, and `network`. Those tests require an environment you control and
must be enabled explicitly.

## Build from source

```bash
git clone https://github.com/vitorcamillo/javaDriveless.git
cd javaDriveless
mvn -B -ntp clean test
mvn -B -ntp package
```

The source targets `io.github.vitorcamillo:javadriveless:1.12.2`.
For a local Maven dependency, run `mvn install` in this repository first. This
repository does not promise that the artifact is available from Maven Central.

## Quick start

```java
import io.github.selenium.javaDriverless.JavaDriverless;

JavaDriverless driver = new JavaDriverless("example-profile");
try {
    driver.get("https://example.com");
    System.out.println(driver.getTitle());
} finally {
    driver.quit();
}
```

Profiles can contain cookies, local storage, and other session data. Do not
commit the profile directory. Use separate profiles for separate accounts and
review a service's terms before automating it.

## Scope and limitations

Java Driverless uses a real Chrome/Chromium process and CDP. It does not bypass
CAPTCHAs, account protections, rate limits, or a website's terms. Proxy and
fingerprint options are explicit application configuration; credentials should
be supplied at runtime through a secret manager or environment, never committed.
Browser behavior depends on the selected profile and host environment.

## Related project

The public social automation MCP that uses this library is maintained at
[`social-mcp`](https://github.com/vitorcamillo/social-mcp).

## License

Java Driverless is MIT licensed. See [`LICENSE`](LICENSE) and
[`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES) for upstream attribution.
