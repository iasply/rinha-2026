package org.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.netty.buffer.ByteBuf;
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
import org.apache.lucene.store.NIOFSDirectory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        if (args.length > 0 && "build-index".equals(args[0])) {
            buildIndex();
            return;
        }

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
        List<TransactionRequest> payloads = loadWarmupPayloads();
        if (payloads.isEmpty()) {
            System.out.println("[warmup] No payloads found, skipping internal warmup");
            return;
        }
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

    private static List<TransactionRequest> loadWarmupPayloads() {
        try (var is = Main.class.getClassLoader().getResourceAsStream("example-payloads.json")) {
            if (is == null)
                return Collections.emptyList();
            JsonNode root = PLAIN_MAPPER.readTree(is);
            List<TransactionRequest> list = new ArrayList<>();
            for (JsonNode node : root) {
                list.add(READER.readValue(node.traverse()));
            }
            return list;
        } catch (Exception e) {
            System.err.println("[warmup] Failed to load payloads: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public static void buildIndex() throws Exception {
        long start = System.nanoTime();
        var directory = new NIOFSDirectory(INDEX_PATH);
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
        var writer = new IndexWriter(directory, config);
        VectorLoader.load(writer, "references.json");
        writer.forceMerge(1);
        writer.commit();
        writer.close();
        directory.close();
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

        req.bodyHandler(body -> {
            TransactionRequest txReq;
            try {
                ByteBuf buf = body.getByteBuf();
                if (buf.hasArray()) {
                    txReq = READER.readValue(buf.array(), buf.arrayOffset() + buf.readerIndex(), buf.readableBytes());
                } else {
                    txReq = READER.readValue(body.getBytes());
                }
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
        });
    }
}
