package org.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

public class Vectorizer {

    private static final float MAX_AMOUNT          = 10_000f;
    private static final float MAX_INSTALLMENTS    = 12f;
    private static final float AMOUNT_VS_AVG_RATIO = 10f;
    private static final float MAX_MINUTES         = 1440f;
    private static final float MAX_KM              = 1000f;
    private static final float MAX_TX_COUNT_24H    = 20f;
    private static final float MAX_MERCHANT_AVG    = 10_000f;

    private final Map<String, Float> mccRisk;

    public Vectorizer() {
        this.mccRisk = loadMccRisk();
    }

    public float[] vectorize(TransactionRequest req) {
        float[] v = new float[14];

        v[0]  = cap(req.transaction().amount() / MAX_AMOUNT);
        v[1]  = cap(req.transaction().installments() / MAX_INSTALLMENTS);
        v[2]  = cap((req.transaction().amount() / req.customer().avgAmount()) / AMOUNT_VS_AVG_RATIO);
        v[3]  = hourOfDay(req.transaction().requestedAt()) / 23f;
        v[4]  = dayOfWeek(req.transaction().requestedAt()) / 6f;

        if (req.lastTransaction() != null) {
            v[5] = cap(req.lastTransaction().secondsAgo() / 60f / MAX_MINUTES);
            v[6] = cap(req.lastTransaction().kmFromCurrent() / MAX_KM);
        } else {
            v[5] = -1f;
            v[6] = -1f;
        }

        v[7]  = cap(req.terminal().kmFromHome() / MAX_KM);
        v[8]  = cap(req.customer().txCount24h() / MAX_TX_COUNT_24H);
        v[9]  = req.terminal().isOnline() ? 1f : 0f;
        v[10] = req.terminal().cardPresent() ? 1f : 0f;
        v[11] = req.customer().knownMerchants().contains(req.merchant().id()) ? 0f : 1f;
        v[12] = mccRisk.getOrDefault(req.merchant().mcc(), 0.5f);
        v[13] = cap(req.merchant().avgAmount() / MAX_MERCHANT_AVG);

        return v;
    }

    private float hourOfDay(String iso) {
        return Instant.parse(iso).atOffset(ZoneOffset.UTC).getHour();
    }

    private float dayOfWeek(String iso) {
        return Instant.parse(iso).atOffset(ZoneOffset.UTC).getDayOfWeek().getValue() - 1;
    }

    private float cap(double v) {
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    private Map<String, Float> loadMccRisk() {
        Map<String, Float> map = new HashMap<>();
        try (var is = getClass().getClassLoader().getResourceAsStream("mcc_risk.json")) {
            if (is == null) return map;
            new ObjectMapper().readTree(is)
                    .fields()
                    .forEachRemaining(e -> map.put(e.getKey(), (float) e.getValue().asDouble()));
        } catch (Exception ignored) {}
        return map;
    }
}
