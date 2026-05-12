package org.vision;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.store.MMapDirectory;

import java.nio.file.Path;

public class FraudIndex {

    private static final float THRESHOLD = 0.6f;
    private static final int TOP_K = 5;
    private static final String INDEX_PATH =
            System.getenv().getOrDefault("INDEX_PATH", "fraud-index");

    private static volatile FraudIndex instance;

    private final IndexSearcher searcher;
    private final Vectorizer vectorizer;

    private FraudIndex() {
        try {
            var dir = new MMapDirectory(Path.of(INDEX_PATH));
            this.searcher = new IndexSearcher(DirectoryReader.open(dir));
            this.vectorizer = new Vectorizer();
            System.out.println("FraudIndex opened at: " + Path.of(INDEX_PATH).toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[FraudIndex] Failed to open index at: " + INDEX_PATH + " — " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to open fraud index at: " + INDEX_PATH, e);
        }
    }

    public static FraudIndex getInstance() {
        if (instance == null) {
            synchronized (FraudIndex.class) {
                if (instance == null) instance = new FraudIndex();
            }
        }
        return instance;
    }

    public record FraudResult(boolean approved, float fraudScore, long vecNs, long knnNs) {}

    public FraudResult evaluate(TransactionRequest req) {
        try {
            long t0 = System.nanoTime();
            float[] vector = vectorizer.vectorize(req);
            long t1 = System.nanoTime();

            var query = new KnnFloatVectorQuery("vector", vector, TOP_K);
            var topDocs = searcher.search(query, TOP_K);
            long t2 = System.nanoTime();

            var storedFields = searcher.storedFields();
            float fraudCount = 0;
            for (var scoreDoc : topDocs.scoreDocs) {
                if ("fraud".equals(storedFields.document(scoreDoc.doc).get("label"))) {
                    fraudCount++;
                }
            }

            float score = fraudCount / TOP_K;
            return new FraudResult(score < THRESHOLD, score, t1 - t0, t2 - t1);
        } catch (Exception e) {
            System.err.println("[FraudIndex] Search failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Search failed", e);
        }
    }
}
