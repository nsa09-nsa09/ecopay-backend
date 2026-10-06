# Dependency, secret and container scanning

CI job `security-scan` (`.github/workflows/ci.yml`) gates image build and deploy.

| Check | Tool | Blocks the build when |
|---|---|---|
| Secrets in git history | gitleaks (`.gitleaks.toml`) | any finding outside the allowlisted test fixtures / `*.example` templates |
| Maven dependencies | Trivy `fs` | CRITICAL or HIGH CVE **with an available fix** |
| Container image | Trivy `image` | CRITICAL or HIGH CVE **with an available fix** in the runtime image |
| Migrations | `scripts/check-flyway-duplicates.ps1` + `mvnw verify` | duplicate versions, or any migration that fails on PostgreSQL 16 (Testcontainers applies the full chain) |

Why unfixed / lower-severity CVEs do not block: they cannot be remediated by a version bump, so
failing on them would only teach people to disable the gate. They are still printed in the job log
and must be reviewed when a fix appears. A finding that is exploitable in EcoPay's actual usage must
be fixed or explicitly suppressed in `.trivyignore` with a dated justification — never by removing
the scan.

## 2026-10-06 dependency refresh

Local `trivy fs --severity CRITICAL,HIGH --ignore-unfixed` reported 64 fixable findings (10
critical) inherited from Spring Boot 4.0.2's managed versions (Tomcat 11.0.15, Netty 4.2.9, Jackson
2.20.2 / 3.0–3.1.0, Spring Security 7.0.2, Spring MVC 7.0.3, Micrometer 1.16.2, PostgreSQL JDBC
42.7.9) plus jsoup 1.18.3. Fixed by upgrading the parent to Spring Boot 4.0.8, pinning
`tomcat.version` 11.0.26, `jackson-bom.version` 3.1.7, `jackson-2-bom.version` 2.21.7 and jsoup
1.23.2. Re-scan: 0 fixable CRITICAL/HIGH. Full test suite and the mock-provider load smoke were
re-run on the new versions.
