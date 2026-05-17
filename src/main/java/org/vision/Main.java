package org.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.MMapDirectory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main extends AbstractVerticle {

    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final String INSTANCE_ID = System.getenv().getOrDefault("INSTANCE_ID", "1");
    private static final int EVENT_LOOP_POOL_SIZE = Integer.parseInt(System.getenv().getOrDefault("EVENT_LOOP_POOL_SIZE", String.valueOf(Runtime.getRuntime().availableProcessors() * 2)));
    private static final int INTERNAL_WARMUP_ROUNDS = Integer.parseInt(System.getenv().getOrDefault("INTERNAL_WARMUP_ROUNDS", "0"));
    private static final Path INDEX_PATH = Path.of("fraud-index");
    private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper().registerModule(new SimpleModule().addDeserializer(TransactionRequest.class, new TransactionRequestDeserializer()));
    private static final ObjectReader READER = PLAIN_MAPPER.readerFor(TransactionRequest.class);
    private static final AtomicBoolean READY = new AtomicBoolean(false);
    private static volatile FraudIndex fraudIndex;

    public static void main(String[] args) throws Exception {

        fraudIndex = FraudIndex.getInstance();

        if (INTERNAL_WARMUP_ROUNDS > 0) {
            runInternalWarmup(fraudIndex);
        }

        Vertx vertx = Vertx.vertx(new VertxOptions().setPreferNativeTransport(true).setEventLoopPoolSize(EVENT_LOOP_POOL_SIZE));
        vertx.deployVerticle(() -> new Main(), new DeploymentOptions().setInstances(EVENT_LOOP_POOL_SIZE))
                .onSuccess(id -> System.out.println("Deployed " + EVENT_LOOP_POOL_SIZE + " instances — instance=" + INSTANCE_ID + " port=" + PORT))
                .onFailure(Throwable::printStackTrace);
    }

    private static void runInternalWarmup(FraudIndex fi) {
        List<TransactionRequest> payloads = generateWarmupPayloads(64);
        int total = payloads.size() * INTERNAL_WARMUP_ROUNDS;
        System.out.println("[warmup] Starting: " + payloads.size() + " payloads x " + INTERNAL_WARMUP_ROUNDS + " rounds = " + total + " evaluations");
        long start = System.nanoTime();
        for (int round = 0; round < INTERNAL_WARMUP_ROUNDS; round++) {
            for (TransactionRequest tx : payloads) {
                fi.evaluate(tx);
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[warmup] Done: " + total + " evaluations in " + elapsedMs + "ms (" + (elapsedMs > 0 ? total * 1000 / elapsedMs : "∞") + " eval/s)");
    }

    private static List<TransactionRequest> generateWarmupPayloads(int n) {
        List<TransactionRequest> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float amount        = 10f + (i * 137.5f % 9990f);
            int installments    = (i % 12) + 1;
            int hour            = i % 24;
            int dayOfWeek       = i % 7;
            long epoch          = 1_700_000_000L + (long) i * 3600;
            float avgAmount     = amount * 0.8f + (i % 5) * 50f;
            int txCount24h      = i % 30;
            String mid          = "merchant-" + (i % 20);
            String mcc          = String.format("%04d", (i * 17) % 9999 + 1);
            float merchantAvg   = 50f + (i * 73f % 500f);
            float kmFromHome    = i * 7.3f % 200f;
            TransactionRequest.LastTransaction lastTx = (i % 4 == 0) ? null
                    : new TransactionRequest.LastTransaction(i * 60 % 86400, i * 1.5f % 50f);
            list.add(new TransactionRequest(
                    "warmup-" + i,
                    new TransactionRequest.Transaction(amount, installments, hour, dayOfWeek, epoch),
                    new TransactionRequest.Customer(avgAmount, txCount24h, Set.of("merchant-" + (i % 10), "merchant-" + ((i + 3) % 10))),
                    new TransactionRequest.Merchant(mid, mcc, merchantAvg),
                    new TransactionRequest.Terminal((i % 2) == 0, (i % 3) != 0, kmFromHome),
                    lastTx
            ));
        }
        return list;
    }

    public static void buildIndex() throws Exception {
        long start = System.nanoTime();
        var config = new IndexWriterConfig(new StandardAnalyzer());
        config.setRAMBufferSizeMB(2048);
        config.setUseCompoundFile(false);
        config.setCodec(new Lucene104Codec() {

            private final KnnVectorsFormat fmt = new Lucene104HnswScalarQuantizedVectorsFormat();

            @Override
            public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
                return fmt;
            }
        });
        try (var directory = new MMapDirectory(INDEX_PATH);
             var writer = new IndexWriter(directory, config)) {
            VectorLoader.load(writer, "references.json");
            writer.forceMerge(1);
            writer.commit();
        }
        System.out.printf("Index built in %.2f s%n", (System.nanoTime() - start) / 1_000_000_000.0);
    }

    @Override
    public void start() {
        vertx.createHttpServer(new HttpServerOptions().setPort(PORT).setTcpNoDelay(true).setReusePort(true).setAcceptBacklog(1024))
                .requestHandler(this::handle)
                .listen(PORT)
                .onSuccess(s -> {
                    READY.set(true);
                    System.out.println("Listening — instance=" + INSTANCE_ID);
                })
                .onFailure(Throwable::printStackTrace);
    }

    private void handle(HttpServerRequest req) {
        var path = req.path();
        var response = req.response();

        if ("/ready".equals(path)) {
            boolean ready = READY.get();
            response.setStatusCode(ready ? 200 : 503).end(ready ? "{\"status\":\"ready\"}" : "{\"status\":\"warming-up\"}");
            return;
        }
        if ("/fraud-score".equals(path) && req.method() == HttpMethod.POST) {
            handleFraudScore(req);
            return;
        }

        response.setStatusCode(404).end();
    }

    private void handleFraudScore(HttpServerRequest req) {
        var response = req.response();

        req.body().onSuccess(body -> {
            TransactionRequest txReq;
            try {
                txReq = READER.readValue(body.getBytes());
            } catch (Exception e) {
                System.err.println("[fraud-score] JSON parse error: " + e.getMessage());
                response.setStatusCode(400).end("Invalid JSON: " + e.getMessage());
                return;
            }

            try {
                response.putHeader("Content-Type", "application/json").end(fraudIndex.evaluate(txReq));
            } catch (Exception e) {
                System.err.println("[fraud-score] error tx=" + txReq.id() + ": " + e.getMessage());
                response.setStatusCode(500).end("{\"error\":\"search failed\"}");
            }
        }).onFailure(err -> {
            System.err.println("[fraud-score] body read error: " + err.getMessage());
            response.setStatusCode(500).end();
        });
    }
}
