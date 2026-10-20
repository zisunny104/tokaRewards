package dev.toka.rewards.core;

import com.google.gson.*;
import dev.toka.storage.DocumentStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** All store access is serialized on one worker. Payment completion is durable and idempotent. */
public final class RewardsService implements AutoCloseable {
    public interface Payment { CompletableFuture<Boolean> deposit(UUID player, String currency, long amount, String operation); }
    public record Result(String status, String id, long amount, int streak) {}
    private final DocumentStore store;
    private final Payment payments;
    private final Clock clock;
    private final ExecutorService worker;
    private final Map<String, CompletableFuture<Result>> active = new HashMap<>();
    private final LinkedHashMap<UUID, JsonObject> profiles = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashSet<String> pending = new LinkedHashSet<>();
    private final Consumer<Throwable> errors;
    private volatile RewardSettings settings;
    private volatile boolean accepting = true;

    public RewardsService(DocumentStore store, RewardSettings settings, Clock clock, Payment payments, Consumer<Throwable> errors) throws IOException {
        this.store = store; this.settings = settings; this.clock = clock; this.payments = payments; this.errors = errors;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(settings.maxPending()), job -> {
            Thread thread = new Thread(job, "tokaRewards-store"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        JsonObject index = store.load("pending").orElseGet(JsonObject::new);
        if (index.has("ids")) for (JsonElement id : index.getAsJsonArray("ids")) pending.add(id.getAsString());
    }

    public RewardSettings settings() { return settings; }
    public void settings(RewardSettings next) { settings = next; }

    public CompletableFuture<JsonObject> profile(UUID player) {
        return work(() -> readProfile(player).deepCopy());
    }

    public CompletableFuture<Result> daily(UUID player, String name, long votes) {
        CompletableFuture<Result> answer = new CompletableFuture<>();
        enqueue(answer, () -> {
            RewardSettings rule = settings;
            if (!rule.dailyEnabled()) { answer.complete(new Result("DISABLED", "", 0, 0)); return; }
            LocalDate today = LocalDate.now(clock.withZone(rule.zone()));
            String operation = "daily:" + today;
            String key = claimKey(player, "daily", operation);
            JsonObject old = readProfile(player);
            String last = old.get("lastDay").getAsString();
            if (!last.isEmpty() && !LocalDate.parse(last).isBefore(today)) {
                Optional<JsonObject> claim = store.load(key);
                if (claim.isPresent() && !"PAID".equals(claim.get().get("state").getAsString())) {
                    dispatch(key, claim.get(), answer);
                } else answer.complete(new Result("ALREADY", key, 0, old.get("streak").getAsInt()));
                return;
            }
            int streak = !last.isEmpty() && LocalDate.parse(last).plusDays(1).equals(today)
                    ? Math.addExact(old.get("streak").getAsInt(), 1) : 1;
            long base = streak > 1 ? rule.consecutive() : rule.base();
            long bonus = votes >= rule.voteThreshold() && votes > 0
                    ? java.math.BigDecimal.valueOf(base).multiply(rule.voteMultiplier()).setScale(0, java.math.RoundingMode.DOWN).longValueExact() : 0;
            JsonObject profile = old.deepCopy();
            profile.addProperty("name", name); profile.addProperty("lastDay", today.toString());
            profile.addProperty("streak", streak); profile.addProperty("total", Math.addExact(old.get("total").getAsLong(), 1));
            JsonObject claim = claim(player, "daily", operation, rule.currency(), Math.addExact(base, bonus));
            claim.addProperty("streak", streak); claim.addProperty("votes", votes);
            prepare(key, claim, Map.of(profileKey(player), profile));
            cache(player, profile);
            dispatch(key, claim, answer);
        });
        return answer;
    }

    /** Trusted-plugin API: namespace and operation are stable IDs, never player-supplied commands. */
    public CompletableFuture<Result> grant(UUID player, String namespace, String operation, String currency, long amount) {
        CompletableFuture<Result> answer = new CompletableFuture<>();
        enqueue(answer, () -> {
            if (namespace == null || !namespace.matches("[a-z0-9._-]{1,40}") || "daily".equals(namespace)
                    || operation == null || operation.isBlank() || operation.length() > 120 || operation.chars().anyMatch(Character::isISOControl)
                    || currency == null || !currency.matches("[a-z0-9]{2,16}") || amount <= 0) throw new IllegalArgumentException("Invalid reward");
            String key = claimKey(player, namespace, operation);
            JsonObject record = store.load(key).orElse(null);
            if (record == null) {
                record = claim(player, namespace, operation, currency, amount);
                prepare(key, record, Map.of());
            } else if (!record.get("currency").getAsString().equals(currency) || record.get("amount").getAsLong() != amount) {
                throw new IllegalArgumentException("Reward ID reused with different content");
            }
            dispatch(key, record, answer);
        });
        return answer;
    }

    private JsonObject claim(UUID player, String namespace, String operation, String currency, long amount) {
        JsonObject record = new JsonObject();
        record.addProperty("uuid", player.toString()); record.addProperty("namespace", namespace);
        record.addProperty("operation", operation); record.addProperty("currency", currency);
        record.addProperty("amount", amount); record.addProperty("state", "PENDING");
        record.addProperty("created", clock.millis()); return record;
    }

    private void prepare(String key, JsonObject record, Map<String, JsonObject> other) throws IOException {
        if (pending.size() >= settings.maxPending()) throw new IllegalStateException("Pending reward limit reached");
        LinkedHashSet<String> next = new LinkedHashSet<>(pending); next.add(key);
        Map<String, JsonObject> batch = new LinkedHashMap<>(other);
        batch.put(key, record); batch.put("pending", index(next));
        store.saveAll(batch); pending.clear(); pending.addAll(next);
    }

    private void dispatch(String key, JsonObject record, CompletableFuture<Result> answer) {
        int streak = record.has("streak") ? record.get("streak").getAsInt() : 0;
        if ("PAID".equals(record.get("state").getAsString())) {
            answer.complete(new Result("ALREADY", key, record.get("amount").getAsLong(), streak)); return;
        }
        CompletableFuture<Result> existing = active.get(key);
        if (existing != null) { existing.whenComplete((value, error) -> complete(answer, value, error)); return; }
        active.put(key, answer);
        CompletableFuture<Boolean> payment;
        try { payment = payments.deposit(UUID.fromString(record.get("uuid").getAsString()), record.get("currency").getAsString(), record.get("amount").getAsLong(), key); }
        catch (Throwable error) { payment = CompletableFuture.failedFuture(error); }
        payment.whenComplete((paid, error) -> enqueue(answer, () -> {
            active.remove(key);
            if (error != null || !Boolean.TRUE.equals(paid)) {
                if (error != null) errors.accept(error);
                answer.complete(new Result("PENDING", key, record.get("amount").getAsLong(), streak)); return;
            }
            JsonObject finished = record.deepCopy(); finished.addProperty("state", "PAID"); finished.addProperty("paid", clock.millis());
            LinkedHashSet<String> next = new LinkedHashSet<>(pending); next.remove(key);
            store.saveAll(Map.of(key, finished, "pending", index(next)));
            pending.clear(); pending.addAll(next);
            answer.complete(new Result("PAID", key, record.get("amount").getAsLong(), streak));
        }));
    }

    public CompletableFuture<Integer> retryPending() {
        return work(() -> {
            int count = 0;
            for (String key : List.copyOf(pending)) {
                if (count >= settings.batchSize()) break;
                JsonObject record = store.load(key).orElseThrow(() -> new IOException("Missing pending reward"));
                dispatch(key, record, new CompletableFuture<>()); count++;
            }
            return count;
        });
    }

    public CompletableFuture<Integer> pendingCount() { return work(pending::size); }
    public CompletableFuture<Void> automatic(UUID player, boolean enabled) {
        return work(() -> { JsonObject profile = readProfile(player).deepCopy(); profile.addProperty("automatic", enabled);
            store.save(profileKey(player), profile); cache(player, profile); return null; });
    }

    /** General profile upsert API, empty-or-equal only; callers transform their own source formats. */
    public CompletableFuture<Integer> importProfiles(String json) {
        return work(() -> {
            if (settings.dailyEnabled()) throw new IllegalStateException("Disable daily rewards before importing profiles");
            JsonArray rows = JsonParser.parseString(json).getAsJsonArray();
            Map<String, JsonObject> batch = new LinkedHashMap<>();
            for (JsonElement item : rows) {
                JsonObject row = item.getAsJsonObject().deepCopy();
                UUID uuid = UUID.fromString(row.get("uuid").getAsString());
                String last = row.get("lastDay").getAsString(); if (!last.isEmpty()) LocalDate.parse(last);
                if (row.get("streak").getAsInt() < 0 || row.get("total").getAsLong() < 0) throw new IllegalArgumentException("Negative sign-in count");
                row.get("automatic").getAsBoolean(); row.get("name").getAsString();
                String key = profileKey(uuid);
                if (batch.containsKey(key)) throw new IllegalArgumentException("Duplicate UUID in import");
                Optional<JsonObject> present = store.load(key);
                if (present.isPresent() && !present.get().equals(row)) throw new IllegalStateException("Existing profile differs: " + uuid);
                batch.put(key, row);
            }
            // Validate all rows before writing any batch; startup imports are resumable and idempotent.
            Map<String, JsonObject> slice = new LinkedHashMap<>();
            for (var entry : batch.entrySet()) {
                slice.put(entry.getKey(), entry.getValue());
                if (slice.size() >= settings.batchSize()) { store.saveAll(slice); slice.clear(); }
            }
            if (!slice.isEmpty()) store.saveAll(slice);
            for (var entry : batch.entrySet()) if (!store.load(entry.getKey()).orElseThrow().equals(entry.getValue())) throw new IOException("Profile readback differs");
            profiles.clear(); return batch.size();
        });
    }

    private JsonObject readProfile(UUID uuid) throws IOException {
        JsonObject profile = profiles.get(uuid);
        if (profile != null) return profile;
        profile = store.load(profileKey(uuid)).orElseGet(() -> {
            JsonObject value = new JsonObject(); value.addProperty("uuid", uuid.toString()); value.addProperty("name", "");
            value.addProperty("lastDay", ""); value.addProperty("streak", 0); value.addProperty("total", 0); value.addProperty("automatic", false); return value;
        });
        cache(uuid, profile); return profile;
    }
    private void cache(UUID uuid, JsonObject profile) {
        profiles.put(uuid, profile);
        while (profiles.size() > settings.cacheSize()) profiles.remove(profiles.keySet().iterator().next());
    }
    private static String profileKey(UUID player) { return "p-" + player; }
    private static JsonObject index(Collection<String> ids) {
        JsonObject doc = new JsonObject(); JsonArray values = new JsonArray(); ids.forEach(values::add); doc.add("ids", values); return doc;
    }
    private static String claimKey(UUID player, String namespace, String operation) {
        try { return "r-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((player + "\n" + namespace + "\n" + operation).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
    }
    private <T> CompletableFuture<T> work(Callable<T> operation) {
        CompletableFuture<T> done = new CompletableFuture<>(); enqueue(done, () -> done.complete(operation.call())); return done;
    }
    private <T> void enqueue(CompletableFuture<T> done, CheckedTask operation) {
        try { worker.execute(() -> { try { operation.run(); } catch (Throwable error) { errors.accept(error); done.completeExceptionally(error); } }); }
        catch (RejectedExecutionException error) { done.completeExceptionally(error); }
    }
    private static <T> void complete(CompletableFuture<T> answer, T value, Throwable error) { if (error == null) answer.complete(value); else answer.completeExceptionally(error); }
    private interface CheckedTask { void run() throws Exception; }
    @Override public void close() throws IOException {
        accepting = false; worker.shutdown();
        try { if (worker.awaitTermination(10, TimeUnit.SECONDS)) store.close(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
