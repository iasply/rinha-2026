package org.vision;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.IndexWriter;

import java.io.InputStream;

public class VectorLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void load(IndexWriter writer, String resource) throws Exception {
        try (InputStream inputStream = VectorLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("err: " + resource);
            }
            MappingIterator<TransactionVector> iterator = OBJECT_MAPPER.readerFor(TransactionVector.class).readValues(inputStream);
            while (iterator.hasNext()) {
                TransactionVector tx = iterator.next();
                Document document = new Document();
                document.add(new KnnFloatVectorField(FraudIndex.VECTOR, tx.vector()));
                document.add(new NumericDocValuesField(FraudIndex.FRAUD_FIELD, "fraud".equals(tx.label()) ? 1L : 0L));
                writer.addDocument(document);
            }
        }
    }
}
