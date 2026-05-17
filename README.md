# rinha-2026

Detecção de fraude via KNN (k=5) sobre 3M vetores indexados com Lucene HNSW.

| Branch | Runtime | Directory | JVM |
|--------|---------|-----------|-----|
| `master` | JVM 25 | MMapDirectory | Eclipse Temurin (HotSpot) |
| `graalvm-test` | JVM 25 | MMapDirectory | GraalVM JIT (jlink minimal) |
| `nativo` | GraalVM CE 24 native | NIOFSDirectory | — |

### Imagens Docker Hub

| Tag | Branch |
|-----|--------|
| `iuryasilva/rinha-2026:latest` | `master` |
| `iuryasilva/rinha-2026:graal` | `graalvm-test` |

### Rodar localmente

```sh
./run.sh
```

### Publicar imagens

```sh
./deploy.sh push
```
