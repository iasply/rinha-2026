package org.vision;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

public class Vectorizer {

    private static final float INV_MAX_AMOUNT        = 1f / 10_000f;
    private static final float INV_MAX_INSTALLMENTS  = 1f / 12f;
    private static final float INV_AVG_RATIO         = 1f / 10f;
    private static final float INV_23                = 1f / 23f;
    private static final float INV_6                 = 1f / 6f;
    private static final float INV_60_MINUTES        = 1f / (60f * 1440f);
    private static final float INV_MAX_KM            = 1f / 1000f;
    private static final float INV_MAX_TX_COUNT_24H  = 1f / 20f;
    private static final float INV_MAX_MERCHANT_AVG  = 1f / 10_000f;

    private final Map<String, Float> mccRisk;

    public Vectorizer() {
        this.mccRisk = loadMccRisk();
    }

    private static float cap(float v) {
        return v <= 0.0f ? 0.0f : v >= 1.0f ? 1.0f : v;
    }

    public float[] vectorize(TransactionRequest req) {
        float[] v = new float[14];

        v[0]  = cap(req.transaction().amount() * INV_MAX_AMOUNT);
        v[1]  = cap(req.transaction().installments() * INV_MAX_INSTALLMENTS);
        float avg = req.customer().avgAmount();
        v[2]  = avg > 0f ? cap((req.transaction().amount() / avg) * INV_AVG_RATIO) : 0f;
        v[3]  = req.transaction().hour() * INV_23;
        v[4]  = req.transaction().dayOfWeek() * INV_6;

        if (req.lastTransaction() != null) {
            v[5] = cap(req.lastTransaction().secondsAgo() * INV_60_MINUTES);
            v[6] = cap(req.lastTransaction().kmFromCurrent() * INV_MAX_KM);
        } else {
            v[5] = -1f;
            v[6] = -1f;
        }

        v[7]  = cap(req.terminal().kmFromHome() * INV_MAX_KM);
        v[8]  = cap(req.customer().txCount24h() * INV_MAX_TX_COUNT_24H);
        v[9]  = req.terminal().isOnline() ? 1f : 0f;
        v[10] = req.terminal().cardPresent() ? 1f : 0f;
        v[11] = req.customer().knownMerchants().contains(req.merchant().id()) ? 0f : 1f;
        v[12] = mccRisk.getOrDefault(req.merchant().mcc(), 0.5f);
        v[13] = cap(req.merchant().avgAmount() * INV_MAX_MERCHANT_AVG);

        return v;
    }

    private Map<String, Float> loadMccRisk() {
        Map<String, Float> map = new HashMap<>();
        try (var is = getClass().getClassLoader().getResourceAsStream("mcc_risk.json")) {
            if (is == null)
                return map;
            new ObjectMapper().readTree(is).fields().forEachRemaining(e -> map.put(e.getKey(), (float) e.getValue().asDouble()));
        } catch (Exception ignored) {
        }
        return map;
    }
}
