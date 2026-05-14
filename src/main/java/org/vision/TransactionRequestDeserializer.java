package org.vision;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class TransactionRequestDeserializer extends StdDeserializer<TransactionRequest> {

    public TransactionRequestDeserializer() {
        super(TransactionRequest.class);
    }

    private static int sakamoto(int y, int m, int d) {
        int[] t = { 0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4 };
        if (m < 3)
            y--;
        return ((y + y / 4 - y / 100 + y / 400 + t[m - 1] + d) % 7 + 6) % 7;
    }

    private static int c2(String s, int i) {
        return (s.charAt(i) - '0') * 10 + (s.charAt(i + 1) - '0');
    }

    private static int parseInt4(String s, int i) {
        return (s.charAt(i) - '0') * 1000 + (s.charAt(i + 1) - '0') * 100 + (s.charAt(i + 2) - '0') * 10 + (s.charAt(i + 3) - '0');
    }

    @Override
    public TransactionRequest deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        String id = null;
        TransactionRequest.Transaction transaction = null;
        TransactionRequest.Customer customer = null;
        TransactionRequest.Merchant merchant = null;
        TransactionRequest.Terminal terminal = null;
        TransactionRequest.LastTransaction lastTransaction = null;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "id" -> id = p.getText();
                case "transaction" -> transaction = parseTransaction(p);
                case "customer" -> customer = parseCustomer(p);
                case "merchant" -> merchant = parseMerchant(p);
                case "terminal" -> terminal = parseTerminal(p);
                case "last_transaction" -> lastTransaction = parseLastTransaction(p);
                default -> p.skipChildren();
            }
        }

        return new TransactionRequest(id, transaction, customer, merchant, terminal, lastTransaction);
    }

    private TransactionRequest.Transaction parseTransaction(JsonParser p) throws IOException {
        float amount = 0f;
        int installments = 0;
        int hour = 0;
        int dayOfWeek = 0;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "amount" -> amount = p.getFloatValue();
                case "installments" -> installments = p.getIntValue();
                case "requested_at" -> {
                    String iso = p.getText();
                    hour = c2(iso, 11);
                    dayOfWeek = sakamoto(parseInt4(iso, 0), c2(iso, 5), c2(iso, 8));
                }
                default -> p.skipChildren();
            }
        }

        return new TransactionRequest.Transaction(amount, installments, hour, dayOfWeek);
    }

    private TransactionRequest.Customer parseCustomer(JsonParser p) throws IOException {
        float avgAmount = 0f;
        int txCount24h = 0;
        Set<String> knownMerchants = Set.of();

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "avg_amount" -> avgAmount = p.getFloatValue();
                case "tx_count_24h" -> txCount24h = p.getIntValue();
                case "known_merchants" -> {
                    Set<String> set = new HashSet<>();
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        set.add(p.getText());
                    }
                    knownMerchants = set;
                }
                default -> p.skipChildren();
            }
        }

        return new TransactionRequest.Customer(avgAmount, txCount24h, knownMerchants);
    }

    private TransactionRequest.Merchant parseMerchant(JsonParser p) throws IOException {
        String id = null;
        String mcc = null;
        float avgAmount = 0f;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "id" -> id = p.getText();
                case "mcc" -> mcc = p.getText();
                case "avg_amount" -> avgAmount = p.getFloatValue();
                default -> p.skipChildren();
            }
        }

        return new TransactionRequest.Merchant(id, mcc, avgAmount);
    }

    private TransactionRequest.Terminal parseTerminal(JsonParser p) throws IOException {
        boolean isOnline = false;
        boolean cardPresent = false;
        float kmFromHome = 0f;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "is_online" -> isOnline = p.getBooleanValue();
                case "card_present" -> cardPresent = p.getBooleanValue();
                case "km_from_home" -> kmFromHome = p.getFloatValue();
                default -> p.skipChildren();
            }
        }

        return new TransactionRequest.Terminal(isOnline, cardPresent, kmFromHome);
    }

    private TransactionRequest.LastTransaction parseLastTransaction(JsonParser p) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }

        float amount = 0f;
        int secondsAgo = 0;
        float kmFromCurrent = 0f;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "amount" -> amount = p.getFloatValue();
                case "seconds_ago" -> secondsAgo = p.getIntValue();
                case "km_from_current" -> kmFromCurrent = p.getFloatValue();
                default -> p.skipChildren();
            }
        }

        return new TransactionRequest.LastTransaction(amount, secondsAgo, kmFromCurrent);
    }
}
