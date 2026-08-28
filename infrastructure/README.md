# Infrastructure

Deployment manifests live here. The repository ships the container definition
and the compose stack; anything cluster-specific belongs to the environment that
runs it and is deliberately not guessed at here.

| Artefact | Location |
|---|---|
| Backend image | `backend/Dockerfile` |
| Local stack | `docker-compose.yml` |
| CI | `.github/workflows/ci.yml` |
| Release APK | `.github/workflows/release-apk.yml` |

See [../docs/deployment.md](../docs/deployment.md) for requirements, probes and
scaling notes.

## Before a real deployment

Three things this repository intentionally does not decide:

1. **A retention schedule for soft-deleted rows.** They are kept so an in-flight
   sync cannot resurrect them; how long they persist is a policy decision.
2. **An `ObjectStorage` implementation for S3, GCS or Azure Blob.** The local
   filesystem backend does not survive more than one node.
3. **Backup and restore testing** for PostgreSQL and the object store. Meal
   photographs are the most sensitive data in the system.
