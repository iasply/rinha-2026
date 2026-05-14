package org.vision;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.MMapDirectory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

public class FraudIndex {

    public static final String VECTOR = "vector";
    public static final String FRAUD_FIELD = "fraud";
    private static final int TOP_K = 5;
    private static final String INDEX_PATH = System.getenv().getOrDefault("INDEX_PATH", "fraud-index");

    // All possible responses: fraudCount 0..TOP_K → approved = fraudCount < 3 (score < 0.6)
    private static final String[] RESPONSES = { "{\"approved\":true,\"fraud_score\":0.0}", "{\"approved\":true,\"fraud_score\":0.2}", "{\"approved\":true,\"fraud_score\":0.4}",
            "{\"approved\":false,\"fraud_score\":0.6}", "{\"approved\":false,\"fraud_score\":0.8}", "{\"approved\":false,\"fraud_score\":1.0}", };

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
                if (instance == null)
                    instance = new FraudIndex();
            }
        }
        return instance;
    }

    public String evaluate(TransactionRequest req) {
        try {
            float[] vector = vectorizer.vectorize(req);

            var query = new KnnFloatVectorQuery(VECTOR, vector, TOP_K);
            var topDocs = searcher.search(query, TOP_K);

            ScoreDoc[] hits = topDocs.scoreDocs;
            Arrays.sort(hits, Comparator.comparingInt(h -> h.doc));

            NumericDocValues fraudDv = MultiDocValues.getNumericValues(searcher.getIndexReader(), FRAUD_FIELD);
            int fraudCount = 0;
            for (ScoreDoc hit : hits) {
                if (fraudDv != null && fraudDv.advanceExact(hit.doc) && fraudDv.longValue() == 1L) {
                    fraudCount++;
                }
            }

            return RESPONSES[fraudCount];
        } catch (Exception e) {
            System.err.println("[FraudIndex] Search failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Search failed", e);
        }
    }
}
