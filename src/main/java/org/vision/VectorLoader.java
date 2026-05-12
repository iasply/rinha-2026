package org.vision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class VectorLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void load(IndexWriter writer, String resource) throws Exception {

        long inicio = System.nanoTime();

        InputStream inputStream = VectorLoader.class.getClassLoader().getResourceAsStream(resource);

        if (inputStream == null) {
            throw new IllegalArgumentException("Resource não encontrado: " + resource);
        }

        MappingIterator<TransactionVector> iterator = OBJECT_MAPPER.readerFor(TransactionVector.class).readValues(inputStream);

        while (iterator.hasNext()) {

            TransactionVector tx = iterator.next();

            Document document = new Document();

            document.add(new KnnFloatVectorField("vector", tx.vector()));

            document.add(new StringField("label", tx.label(), Field.Store.YES));

            writer.addDocument(document);
        }

        writer.commit();

        long fim = System.nanoTime();

        System.out.printf("Tempo: %.2f ms%n", (fim - inicio) / 1_000_000.0);
    }


}