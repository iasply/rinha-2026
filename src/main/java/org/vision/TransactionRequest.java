package org.vision;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.Set;

@JsonDeserialize(using = TransactionRequestDeserializer.class)
public record TransactionRequest(String id, Transaction transaction, Customer customer, Merchant merchant, Terminal terminal, LastTransaction lastTransaction) {

    public record Transaction(float amount, int installments, int hour, int dayOfWeek, long requestedAtEpoch) {}

    public record Customer(float avgAmount, int txCount24h, Set<String> knownMerchants) {}

    public record Merchant(String id, String mcc, float avgAmount) {}

    public record Terminal(boolean isOnline, boolean cardPresent, float kmFromHome) {}

    public record LastTransaction(int secondsAgo, float kmFromCurrent) {}
}
