package org.vision;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.MMapDirectory;

import java.io.IOException;
import java.nio.file.Path;

public class FraudIndex {

    public static final String VECTOR = "vector";
    public static final String FRAUD_FIELD = "fraud";
    private static final int TOP_K = 5;
    private static final String INDEX_PATH = System.getenv().getOrDefault("INDEX_PATH", "fraud-index");

    private static final String[] RESPONSES = {
        "{\"approved\":true,\"fraud_score\":0.0}",
        "{\"approved\":true,\"fraud_score\":0.2}",
        "{\"approved\":true,\"fraud_score\":0.4}",
        "{\"approved\":false,\"fraud_score\":0.6}",
        "{\"approved\":false,\"fraud_score\":0.8}",
        "{\"approved\":false,\"fraud_score\":1.0}",
    };

    private static volatile FraudIndex instance;

    private final IndexSearcher searcher;
    private final Vectorizer vectorizer;
    private final boolean[] fraudFlags;

    private FraudIndex() {
        try {
            var dir = new MMapDirectory(Path.of(INDEX_PATH));
            var reader = DirectoryReader.open(dir);
            this.searcher = new IndexSearcher(reader);
            this.fraudFlags = loadFraudFlags(reader);
            this.vectorizer = new Vectorizer();
            System.out.println("FraudIndex opened at: " + Path.of(INDEX_PATH).toAbsolutePath() + " (" + reader.maxDoc() + " docs)");
        } catch (Exception e) {
            System.err.println("[FraudIndex] Failed to open index at: " + INDEX_PATH + " — " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to open fraud index at: " + INDEX_PATH, e);
        }
    }

    private static boolean[] loadFraudFlags(DirectoryReader reader) throws IOException {
        boolean[] flags = new boolean[reader.maxDoc()];
        NumericDocValues dv = MultiDocValues.getNumericValues(reader, FRAUD_FIELD);
        if (dv != null) {
            while (dv.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
                flags[dv.docID()] = dv.longValue() == 1L;
            }
        }
        return flags;
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
            ScoreDoc[] hits = searcher.search(new KnnFloatVectorQuery(VECTOR, vector, TOP_K), TOP_K).scoreDocs;
            int fraudCount = 0;
            for (ScoreDoc hit : hits) {
                if (fraudFlags[hit.doc]) fraudCount++;
            }
            return RESPONSES[fraudCount];
        } catch (Exception e) {
            System.err.println("[FraudIndex] Search failed: " + e.getMessage());
            throw new RuntimeException("Search failed", e);
        }
    }
}
