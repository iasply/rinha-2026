package org.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.MMapDirectory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main extends AbstractVerticle {

    private static final int PORT =
            Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final String INSTANCE_ID =
            System.getenv().getOrDefault("INSTANCE_ID", "1");
    private static final int WORKER_POOL_SIZE =
            Integer.parseInt(System.getenv().getOrDefault("WORKER_POOL_SIZE", "10"));
    private static final int EVENT_LOOP_POOL_SIZE =
            Integer.parseInt(System.getenv().getOrDefault("EVENT_LOOP_POOL_SIZE",
                    String.valueOf(Runtime.getRuntime().availableProcessors() * 2)));
    private static final Path INDEX_PATH = Path.of("fraud-index");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new SimpleModule().addDeserializer(
                    TransactionRequest.class, new TransactionRequestDeserializer()));
    private static final AtomicBoolean READY = new AtomicBoolean(false);
    private static volatile FraudIndex fraudIndex;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "build-index".equals(args[0])) {
            buildIndex();
            return;
        }
        Vertx vertx = Vertx.vertx(new VertxOptions()
                .setPreferNativeTransport(true)
                .setWorkerPoolSize(WORKER_POOL_SIZE)
                .setEventLoopPoolSize(EVENT_LOOP_POOL_SIZE));
        vertx.deployVerticle(new Main())
                .onSuccess(id -> System.out.println("Listening — instance=" + INSTANCE_ID + " port=" + PORT))
                .onFailure(Throwable::printStackTrace);
    }

    public static void buildIndex() throws Exception {
        long start = System.nanoTime();
        var directory = new MMapDirectory(INDEX_PATH);
        var config = new IndexWriterConfig(new StandardAnalyzer());
        config.setRAMBufferSizeMB(2048);
        config.setUseCompoundFile(false);
        var writer = new IndexWriter(directory, config);
        VectorLoader.load(writer, "references.json");
        writer.commit();
        writer.close();
        directory.close();
        System.out.printf("Index built in %.2f s%n", (System.nanoTime() - start) / 1_000_000_000.0);
    }

    @Override
    public void start() {
        vertx.createHttpServer(new HttpServerOptions()
                        .setPort(PORT)
                        .setTcpNoDelay(true)
                        .setReusePort(true)
                        .setAcceptBacklog(1024))
                .requestHandler(this::handle)
                .listen(PORT)
                .onSuccess(s -> {
                    System.out.println("Listening — instance=" + INSTANCE_ID);
                    vertx.executeBlocking(() -> {
                        fraudIndex = FraudIndex.getInstance();
                        return null;
                    }).onFailure(e -> System.err.println("[init] FraudIndex failed: " + e.getMessage()));
                });
    }

    private void handle(HttpServerRequest req) {
        var path = req.path();
        var response = req.response();

        if ("/ready".equals(path)) {
            response.setStatusCode(READY.get() ? 200 : 503)
                    .end(READY.get() ? "{\"status\":\"ready\"}" : "{\"status\":\"warming-up\"}");
            return;
        }

        if ("/warmup-done".equals(path)) {
            READY.set(true);
            System.out.println("Warmup done — instance=" + INSTANCE_ID + " ready");
            response.setStatusCode(200).end("{\"status\":\"ready\"}");
            return;
        }

        if ("/hello".equals(path)) {
            response.putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                            .put("message", "Hello, World!")
                            .put("instance", INSTANCE_ID)
                            .encode());
            return;
        }

        if ("/fraud-score".equals(path) && "POST".equals(req.method().name())) {
            handleFraudScore(req);
            return;
        }

        response.setStatusCode(404).end();
    }

    private record Eval(FraudIndex.FraudResult fraud, long parseNs, long queueNs) {}

    private void handleFraudScore(HttpServerRequest req) {
        var response = req.response();
        final long tStart = System.nanoTime();

        req.bodyHandler(body -> {
            final long tBody = System.nanoTime();
            TransactionRequest txReq;
            try {
                txReq = MAPPER.readValue(body.getBytes(), TransactionRequest.class);
            } catch (Exception e) {
                System.err.println("[fraud-score] JSON parse error: " + e.getMessage());
                response.setStatusCode(400).end("Invalid JSON: " + e.getMessage());
                return;
            }
            final long tParsed = System.nanoTime();

            vertx.executeBlocking(() -> {
                final long tWorker = System.nanoTime();
                FraudIndex fi = fraudIndex;
                if (fi == null) fi = FraudIndex.getInstance();
                return new Eval(fi.evaluate(txReq), tParsed - tBody, tWorker - tParsed);
            })
            .onSuccess(eval -> {
                final long totalNs = System.nanoTime() - tStart;
                final var r = eval.fraud();
                response
                        .putHeader("Content-Type", "application/json")
                        .putHeader("X-T-Parse-Us",  String.valueOf(eval.parseNs() / 1_000))
                        .putHeader("X-T-Queue-Us",  String.valueOf(eval.queueNs() / 1_000))
                        .putHeader("X-T-Vec-Us",    String.valueOf(r.vecNs()      / 1_000))
                        .putHeader("X-T-Knn-Us",    String.valueOf(r.knnNs()      / 1_000))
                        .putHeader("X-T-Total-Us",  String.valueOf(totalNs        / 1_000))
                        .end("{\"approved\":" + r.approved() + ",\"fraud_score\":" + r.fraudScore() + "}");

                if (totalNs > 20_000_000L) {
                    System.out.printf("[slow] tx=%-24s parse=%5dµs queue=%5dµs vec=%5dµs knn=%5dµs total=%5dµs%n",
                            txReq.id(),
                            eval.parseNs() / 1_000,
                            eval.queueNs() / 1_000,
                            r.vecNs()      / 1_000,
                            r.knnNs()      / 1_000,
                            totalNs        / 1_000);
                }
            })
            .onFailure(e -> {
                System.err.println("[fraud-score] error tx=" + txReq.id() + ": " + e.getMessage());
                response.setStatusCode(500).end("{\"error\":\"search failed\"}");
            });
        });
    }
}
