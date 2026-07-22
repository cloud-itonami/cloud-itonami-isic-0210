# Contributing

`cloud-itonami-isic-0210` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development
The capability layer lives in `kotoba-lang/*` libraries. This repo holds the
business blueprint and operator contracts.

```bash
clojure -M:test
clojure -M:lint
```

## Rules
- Do not commit real operating, personal or credential data.
- Keep field-operation scheduling, stand records and disclosures behind the Forest Coordination Governor.
- Treat workflows as high-risk: add tests for scope-boundary gating (no direct logging-equipment control), record integrity, forest-health-concern escalation and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
