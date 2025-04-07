import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

public class BangladeshTileDownloader {
    
    
    // Bangladesh bounding box
    private static final double MIN_LAT = 20.3756;
    private static final double MAX_LAT = 26.6315;
    private static final double MIN_LON = 88.0083;
    private static final double MAX_LON = 92.6727;
    private static final int MIN_ZOOM = 0;
    private static final int MAX_ZOOM = 14;
    
    // Thread pool configuration
    private static final int THREAD_POOL_SIZE = 4;
    private static final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    private static final RateLimiter rateLimiter = new RateLimiter(THREAD_POOL_SIZE);
    
    public static void main(String[] args) {
        System.out.println("Starting parallel tile download for Bangladesh...");
        
        // Process each zoom level
        IntStream.rangeClosed(MIN_ZOOM, MAX_ZOOM).forEach(zoom -> {
            System.out.println("Processing zoom level: " + zoom);
            TileBounds bounds = calculateTileBounds(zoom);
            
            // Submit download tasks for all tiles in this zoom level
            IntStream.rangeClosed(bounds.xMin, bounds.xMax).forEach(x -> {
                IntStream.rangeClosed(bounds.yMin, bounds.yMax).forEach(y -> {
                    executor.submit(() -> downloadTile(zoom, x, y));
                });
            });
        });
        
        // Shutdown the executor and wait for completion
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
            System.out.println("All downloads completed!");
        } catch (InterruptedException e) {
            System.err.println("Download was interrupted");
        }
    }
    
    private static void downloadTile(int zoom, int x, int y) {
        String url = String.format("https://tile.openstreetmap.org/%d/%d/%d.png", zoom, x, y);
        String path = String.format("bangladesh-tiles/%d/%d", zoom, x);
        String filePath = String.format("%s/%d.png", path, y);
        
        // Skip if file already exists
        if (Files.exists(Paths.get(filePath))) {
            return;
        }
        
        try {
            // Rate limit - important to avoid being blocked
            rateLimiter.acquire();
            
            // Create directories if needed
            Files.createDirectories(Paths.get(path));
            
            // Configure HTTP connection
            URL tileUrl = URI.create(url).toURL();
            HttpURLConnection connection = (HttpURLConnection) tileUrl.openConnection();
            connection.setRequestProperty("User-Agent", "BangladeshOfflineMap/1.0 (contact@example.com)");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            
            // Download the tile
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(filePath)) {
                
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                
                System.out.printf("Downloaded: z=%d x=%d y=%d%n", zoom, x, y);
            }
        } catch (IOException | InterruptedException e) {
            System.err.printf("Failed to download z=%d x=%d y=%d: %s%n", zoom, x, y, e.getMessage());
        }
    }
    
    // Helper class for rate limiting
    private static class RateLimiter {
        private final Semaphore semaphore;
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        
        public RateLimiter(int permits) {
            this.semaphore = new Semaphore(permits);
            scheduler.scheduleAtFixedRate(() -> semaphore.release(permits), 1, 1, TimeUnit.SECONDS);
        }
        
        public void acquire() throws InterruptedException {
            semaphore.acquire();
        }
    }
    
    private static TileBounds calculateTileBounds(int zoom) {
        int[] minCoords = latLonToTile(MIN_LAT, MIN_LON, zoom);
        int[] maxCoords = latLonToTile(MAX_LAT, MAX_LON, zoom);
        
        return new TileBounds(
            Math.min(minCoords[0], maxCoords[0]),
            Math.max(minCoords[1], maxCoords[1]),
            Math.max(minCoords[0], maxCoords[0]),
            Math.min(minCoords[1], maxCoords[1])
        );
    }
    
    private static class TileBounds {
        final int xMin;
        final int yMax;
        final int xMax;
        final int yMin;
        
        TileBounds(int xMin, int yMax, int xMax, int yMin) {
            this.xMin = xMin;
            this.yMax = yMax;
            this.xMax = xMax;
            this.yMin = yMin;
        }
    }
    
    private static int[] latLonToTile(double lat, double lon, int zoom) {
        double n = Math.pow(2, zoom);
        int xTile = (int) (((lon + 180.0) / 360.0) * n);
        
        // Completely unambiguous version:
        double latRad = Math.toRadians(lat);
        double tangent = Math.tan(latRad);
        double secant = 1.0 / Math.cos(latRad);
        double logPart = Math.log(tangent + secant) / Math.PI;
        int yTile = (int) (((1.0 - logPart) / 2.0) * n);
        
        return new int[]{xTile, yTile};
    }
    
}