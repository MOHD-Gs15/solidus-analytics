# Security notes

## Secrets

Do not commit `SOLIDUS_GITHUB_TOKEN`, `SOLIDUS_LICENSE_SECRET`, `SOLIDUS_DASHBOARD_PASSWORD`, Discord webhook URLs, license files, server databases, or runtime logs. GitHub publishing reads `SOLIDUS_GITHUB_TOKEN` from the server environment and does not persist it in `dashboard.properties`.

If a secret is exposed, revoke it immediately and issue a replacement with the smallest possible scope. For GitHub publishing, `Contents: Read and write` on the target repository is normally sufficient; full-account permissions are not required.

## Dashboard exposure

The embedded dashboard binds to `127.0.0.1` and is disabled by default. Do not expose it directly to the public Internet. If remote access is required, put it behind an HTTPS reverse proxy with an additional access-control layer.

## Licensing

The runtime verifier requires `SOLIDUS_LICENSE_SECRET`. The signing secret must remain in a private licensing service or private operator-only tool. A signing key must never be included in the public mod JAR or repository.
