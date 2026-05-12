package org.vision;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TransactionRequestDeserializer extends StdDeserializer<TransactionRequest> {

    public TransactionRequestDeserializer() {
        super(TransactionRequest.class);
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
                case "id"               -> id = p.getText();
                case "transaction"      -> transaction = parseTransaction(p);
                case "customer"         -> customer = parseCustomer(p);
                case "merchant"         -> merchant = parseMerchant(p);
                case "terminal"         -> terminal = parseTerminal(p);
                case "last_transaction" -> lastTransaction = parseLastTransaction(p);
                default                 -> p.skipChildren();
            }
        }

        return new TransactionRequest(id, transaction, customer, merchant, terminal, lastTransaction);
    }

    private TransactionRequest.Transaction parseTransaction(JsonParser p) throws IOException {
        float amount = 0f;
        int installments = 0;
        String requestedAt = null;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "amount"       -> amount = p.getFloatValue();
                case "installments" -> installments = p.getIntValue();
                case "requested_at" -> requestedAt = p.getText();
                default             -> p.skipChildren();
            }
        }

        return new TransactionRequest.Transaction(amount, installments, requestedAt);
    }

    private TransactionRequest.Customer parseCustomer(JsonParser p) throws IOException {
        float avgAmount = 0f;
        int txCount24h = 0;
        List<String> knownMerchants = List.of();

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "avg_amount"      -> avgAmount = p.getFloatValue();
                case "tx_count_24h"    -> txCount24h = p.getIntValue();
                case "known_merchants" -> {
                    List<String> list = new ArrayList<>();
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        list.add(p.getText());
                    }
                    knownMerchants = list;
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
                case "id"         -> id = p.getText();
                case "mcc"        -> mcc = p.getText();
                case "avg_amount" -> avgAmount = p.getFloatValue();
                default           -> p.skipChildren();
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
                case "is_online"    -> isOnline = p.getBooleanValue();
                case "card_present" -> cardPresent = p.getBooleanValue();
                case "km_from_home" -> kmFromHome = p.getFloatValue();
                default             -> p.skipChildren();
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
                case "amount"          -> amount = p.getFloatValue();
                case "seconds_ago"     -> secondsAgo = p.getIntValue();
                case "km_from_current" -> kmFromCurrent = p.getFloatValue();
                default                -> p.skipChildren();
            }
        }

        return new TransactionRequest.LastTransaction(amount, secondsAgo, kmFromCurrent);
    }
}
