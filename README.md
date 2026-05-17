# rinha-2026

Detecção de fraude via KNN (k=5) sobre 3M vetores indexados com Lucene HNSW.

| Branch | Runtime | Directory | JVM |
|--------|---------|-----------|-----|
| `master` | JVM 25 | MMapDirectory | GraalVM JIT |
| `nativo` | GraalVM CE 24 native | NIOFSDirectory | — |

### Rodar localmente

```sh
./run.sh
```

### Publicar imagens (submission)

```sh
REGISTRY=iasply/rinha-2026 ./deploy.sh push
```
