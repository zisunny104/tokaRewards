package dev.toka.rewards;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Kept free of an Economy class reference so that its absence does not prevent loading. */
final class Payments {
    private Payments() {}
    static CompletableFuture<Boolean> deposit(UUID id, String currency, long amount, String operation) {
        try {
            Class<?> economy = Class.forName("dev.toka.economy.Economy");
            Class<?> cause = Class.forName("dev.toka.economy.Cause");
            Object reason = cause.getConstructor(String.class,String.class).newInstance("reward","tokaRewards");
            CompletableFuture<?> payment = (CompletableFuture<?>) economy.getMethod("depositOnce",UUID.class,String.class,long.class,cause,String.class)
                    .invoke(null,id,currency,amount,reason,operation);
            return payment.thenApply(result -> { try { return Boolean.TRUE.equals(result.getClass().getMethod("ok").invoke(result)); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException("Economy response unavailable"); } });
        } catch (ReflectiveOperationException | LinkageError error) { return CompletableFuture.completedFuture(false); }
    }
}
