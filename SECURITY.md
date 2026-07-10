# Security Policy

## Project context

Easydora is an educational and portfolio project demonstrating
event-driven microservices architecture. It is not deployed as a
production service and does not process real user data. That said,
security issues are taken seriously and reports are appreciated —
finding and fixing them is itself part of demonstrating sound
engineering practice.

## Supported versions

This project does not maintain multiple released versions. Security
reports are evaluated against the latest state of the `main` branch.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security
vulnerabilities.

Preferred method: use
[GitHub Security Advisories](https://github.com/pablofelipe/easydora/security/advisories/new)
to report privately. This lets the report be discussed and fixed
before any public disclosure.

Alternatively, you may report by email to **pablofelipe@gmail.com**.
Please include:

- A description of the vulnerability and its potential impact.
- Steps to reproduce, or a minimal proof of concept.
- The affected service(s) and, if known, the affected file/endpoint.

## Responsible disclosure

Please allow reasonable time to investigate and address a report
before disclosing it publicly. As this is a project maintained on a
best-effort basis, response times are not guaranteed, but reports will
be acknowledged and addressed as promptly as possible.

## Scope notes

Some simplifications are intentional and not considered
vulnerabilities in themselves (though issues in how they're
implemented are still welcome as reports) — for example, the
cross-service JWT broadcast authentication pattern documented in
`README.md`, or the use of a shared Postgres instance with
schema-level isolation (see
[ADR-0018](docs/adr/0018-persistence-strategy.md)). If you believe the
*implementation* of one of these documented trade-offs introduces a
real exploitable issue, please still report it.
