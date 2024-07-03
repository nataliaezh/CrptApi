import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class CrptApi implements AutoCloseable {
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, Integer> requestsCount = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final TimeUnit timeUnit;
    private final long limit;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        this.limit = requestLimit;
        this.baseUrl = "https://ismp.crpt.ru/api/v3/lk/documents/create";
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        scheduleReset();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private void scheduleReset() {
        Runnable resetTask = () -> requestsCount.values().forEach(count -> count = 0);
        scheduler.scheduleAtFixedRate(resetTask, timeUnit.toMillis(1), timeUnit.toMillis(1), TimeUnit.MILLISECONDS);
    }

    private boolean isAllowed(String key) {
        return requestsCount.computeIfAbsent(key, k -> 0) < limit;
    }

    public Document createDocument(Document document, String signature) throws IOException {
        if (!isAllowed("createDocument")) {
            throw new IllegalStateException("Too many requests");
        }

        // Increment the request counter for this operation
        requestsCount.put("createDocument", requestsCount.getOrDefault("createDocument", 0) + 1);

        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");

            byte[] jsonData = mapper.writeValueAsBytes(document);
            connection.getOutputStream().write(jsonData);

            if (signature != null) {
                connection.addRequestProperty("X-Signature", signature);
            }

            connection.connect();

            if (connection.getResponseCode() == HttpURLConnection.HTTP_CREATED) {
                return mapper.readValue(connection.getInputStream(), Document.class);
            } else {
                throw new IOException("Failed to create document: " + connection.getResponseMessage());
            }
        } finally {
            // Decrement the request counter after the operation completes
            requestsCount.put("createDocument", Math.max(requestsCount.get("createDocument") - 1, 0));
        }
    }

    public static class Document {
        private String description;
        private String docId;
        private String docStatus;
        private String docType;
        private boolean importRequest;
        private String ownerInn;
        private String participantInn;
        private String producerInn;
        private String productionDate;
        private String productionType;
        private Product[] products;
        private String regDate;
        private String regNumber;

        // Getters and setters omitted for brevity

        public static class Product {
            private String certificateDocument;
            private String certificate;
        }
    }
}