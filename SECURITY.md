# Security Policy

## Reporting a Vulnerability

This is an open-source Monero miner for ARM64 Android devices.
We take security and user safety seriously.

**Please do not file a public GitHub issue for security vulnerabilities.**

Use **GitHub Security Advisories** (private) on this repository.

You can expect:
- **Acknowledgment** within 72 hours
- **An initial assessment** within 5 business days
- **A fix timeline** once the severity is determined

## Scope

The following are in scope:
- Remote code execution via the bundled XMRig binary
- Wallet address leakage or unintended disclosure
- Bypass of the thermal / charging-only safety mechanisms
- Intent injection or privilege escalation from other apps

The following are **out of scope** (informational only):
- Absence of certificate pinning (TLS with OpenSSL is the design choice)
- Obfuscation / anti-tamper (the project is open source)
- The XMRig upstream binary itself (report those to MoneroOcean)

## Preferred reporting format

If possible, include:
- Android version and device model
- Steps to reproduce
- Whether the issue occurs with a debug or release build
- Relevant logcat output or screenshots

## Disclosure

We prefer coordinated disclosure. We will work with you on timing.