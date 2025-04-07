package com.ithouse.mshop.shop;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

public class BangladeshTileDownloader {

    private static final Logger log = LogManager.getLogger();


    // Bangladesh bounding box coordinates
    private static final double MIN_LAT = 20.3756;
    private static final double MAX_LAT = 26.6315;
    private static final double MIN_LON = 88.0083;
    private static final double MAX_LON = 92.6727;

    // Zoom levels to download
    private static final int MIN_ZOOM = 0;
    private static final int MAX_ZOOM = 14;

    // Rate limiting to comply with OSM's tile usage policy
    private static final int MAX_REQUESTS_PER_SECOND = 2;
    private static final Semaphore rateLimiter = new Semaphore(MAX_REQUESTS_PER_SECOND);

    // Virtual thread control (reduced from 1000 to 500 for better stability)
    private static final int MAX_VIRTUAL_THREADS = 500;
    private static final Semaphore threadLimiter = new Semaphore(MAX_VIRTUAL_THREADS);

    // Global rate limit control
    private static final AtomicBoolean globalRateLimit = new AtomicBoolean(false);
    private static final ScheduledExecutorService globalRateLimitScheduler =
            Executors.newSingleThreadScheduledExecutor();

    // Statistics
    private static final AtomicInteger successCount = new AtomicInteger();
    private static final AtomicInteger skipCount = new AtomicInteger();
    private static final AtomicInteger failCount = new AtomicInteger();

    public static void main(String[] args) {
        log.info("Starting Bangladesh offline map tile download with optimized virtual threads...");

        // Setup rate limiter reset scheduler
        ScheduledExecutorService rateLimiterScheduler = Executors.newSingleThreadScheduledExecutor();
        rateLimiterScheduler.scheduleAtFixedRate(
                () -> rateLimiter.release(MAX_REQUESTS_PER_SECOND - rateLimiter.availablePermits()),
                1, 1, TimeUnit.SECONDS
        );

        // Setup monitoring
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleAtFixedRate(() -> {
            logThreadStats();
            log.info("RateLimiter permits: {}/{}",
                    rateLimiter.availablePermits(), MAX_REQUESTS_PER_SECOND);
            log.info("ThreadLimiter permits: {}/{}",
                    threadLimiter.availablePermits(), MAX_VIRTUAL_THREADS);
        }, 10, 10, TimeUnit.SECONDS);

        long startTime = System.currentTimeMillis();

        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("tile-dl-", 0)
                        .factory()
        )) {
            // Process each zoom level sequentially
            for (int zoom = MIN_ZOOM; zoom <= MAX_ZOOM; zoom++) {
                final int finalZoom = zoom;
                log.info("Processing zoom level {}...", zoom);
                TileBounds bounds = calculateTileBounds(finalZoom);

                // Submit tasks with proper backpressure
                for (int x = bounds.xMin(); x <= bounds.xMax(); x++) {
                    final int finalX = x;
                    for (int y = bounds.yMin(); y <= bounds.yMax(); y++) {
                        final int finalY = y;

                        // Apply backpressure
                        threadLimiter.acquireUninterruptibly();
                        executor.execute(() -> {
                            try {
                                downloadTile(finalZoom, finalX, finalY);
                            } finally {
                                threadLimiter.release();
                            }
                        });
                    }
                }
            }
        } finally {
            // Proper shutdown
            rateLimiterScheduler.shutdown();
            globalRateLimitScheduler.shutdown();
            monitor.shutdown();

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            log.info("Download completed in {} seconds", duration);
            log.info("Success: {}, Skipped: {}, Failed: {}",
                    successCount.get(), skipCount.get(), failCount.get());
        }
    }

    private static void downloadTile(int zoom, int x, int y) {
        String url = String.format("https://tile.openstreetmap.org/%d/%d/%d.png", zoom, x, y);
        Path filePath = Path.of("D:\\my git\\tst-mp\\bangladesh-tiles1",
                String.valueOf(zoom), String.valueOf(x), y + ".png");

        int retryCount = 0;
        final int maxRetries = 3;
        final long baseRetryDelay = 5000;

        try {
            // Skip if file exists
            if (Files.exists(filePath)) {
                skipCount.incrementAndGet();
                return;
            }

            // Check global rate limit
            if (globalRateLimit.get()) {
                sleepWithInterruptCheck(baseRetryDelay);
                return;
            }

            // Rate limiting
            rateLimiter.acquireUninterruptibly();
            try {
                Files.createDirectories(filePath.getParent());

                HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                connection.setRequestProperty("User-Agent", "BangladeshOfflineMap/1.0");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod("GET");

                int responseCode = connection.getResponseCode();

                if (responseCode == 429) { // Too Many Requests
                    activateGlobalRateLimit();
                    retryCount++;
                    return;
                } else if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP error: " + responseCode);
                }

                // Successful response
                try (InputStream in = connection.getInputStream();
                     OutputStream out = Files.newOutputStream(filePath)) {
                    in.transferTo(out);
                    successCount.incrementAndGet();
                    log.info("Downloaded z={} x={} y={}", zoom, x, y);
                }
            } finally {
                rateLimiter.release();
            }
        } catch (Exception e) {
            log.error("Error downloading z={} x={} y={}: {}", zoom, x, y, e.getMessage());
            failCount.incrementAndGet();
        }
    }

    private static void activateGlobalRateLimit() {
        if (globalRateLimit.compareAndSet(false, true)) {
            log.warn("Activating global rate limit");
            globalRateLimitScheduler.schedule(
                    () -> globalRateLimit.set(false),
                    5, TimeUnit.SECONDS
            );
        }
    }

    private static void sleepWithInterruptCheck(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void logThreadStats() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        int activeThreads = threadBean.getThreadCount();
        int daemonThreads = threadBean.getDaemonThreadCount();

        log.info("Thread Stats - Active: {} ({} non-daemon)",
                activeThreads, activeThreads - daemonThreads);
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
    private static int[] latLonToTile(double lat, double lon, int zoom) {
        double n = Math.pow(2, zoom);
        int xTile = (int) (((lon + 180.0) / 360.0) * n);

        double latRad = Math.toRadians(lat);
        double tangent = Math.tan(latRad);
        double secant = 1.0 / Math.cos(latRad);
        double logPart = Math.log(tangent + secant) / Math.PI;
        int yTile = (int) (((1.0 - logPart) / 2.0) * n);

        return new int[]{xTile, yTile};
    }

    private record TileBounds(int xMin, int yMax, int xMax, int yMin) {
    }


}
