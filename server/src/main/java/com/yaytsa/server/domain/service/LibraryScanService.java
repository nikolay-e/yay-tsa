package com.yaytsa.server.domain.service;

import com.yaytsa.server.infra.fs.FileSystemMediaScanner;
import com.yaytsa.server.infra.fs.FileSystemMediaScanner.ScanResult;
import com.yaytsa.server.infra.persistence.entity.LibraryScanEntity;
import com.yaytsa.server.infra.persistence.repository.LibraryScanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LibraryScanService {

    private static final Logger log = LoggerFactory.getLogger(LibraryScanService.class);

    private final FileSystemMediaScanner scanner;
    private final LibraryScanRepository libraryScanRepository;
    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);

    @Value("${yaytsa.media.library.roots:./media}")
    private String libraryRoots;

    @Value("${yaytsa.media.library.scan-on-startup:true}")
    private boolean scanOnStartup;

    public LibraryScanService(
            FileSystemMediaScanner scanner,
            LibraryScanRepository libraryScanRepository
    ) {
        this.scanner = scanner;
        this.libraryScanRepository = libraryScanRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (scanOnStartup) {
            log.info("Application ready, triggering startup library scan");
            triggerFullScan();
        }
    }

    @Async
    public CompletableFuture<List<ScanResult>> triggerFullScan() {
        if (!scanInProgress.compareAndSet(false, true)) {
            log.warn("Scan already in progress, skipping");
            return CompletableFuture.completedFuture(List.of());
        }

        try {
            List<String> roots = Arrays.stream(libraryRoots.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

            log.info("Starting full library scan for {} root(s): {}", roots.size(), roots);

            List<ScanResult> results = roots.stream()
                .map(this::scanLibraryRoot)
                .toList();

            log.info("Full library scan completed");
            return CompletableFuture.completedFuture(results);

        } finally {
            scanInProgress.set(false);
        }
    }

    public ScanResult scanLibraryRoot(String libraryRoot) {
        LibraryScanEntity scanRecord = new LibraryScanEntity();
        scanRecord.setLibraryRoot(libraryRoot);
        scanRecord.setScanType("Full");
        scanRecord.setStatus("Running");
        scanRecord.setStartedAt(OffsetDateTime.now());
        scanRecord = libraryScanRepository.save(scanRecord);

        try {
            ScanResult result = scanner.scan(libraryRoot);

            scanRecord.setFilesScanned(result.filesScanned());
            scanRecord.setFilesAdded(result.filesAdded());
            scanRecord.setFilesUpdated(result.filesUpdated());
            scanRecord.setFilesRemoved(result.filesRemoved());
            scanRecord.setErrorCount(result.errors());
            scanRecord.setStatus("Completed");
            scanRecord.setCompletedAt(OffsetDateTime.now());
            libraryScanRepository.save(scanRecord);

            return result;

        } catch (Exception e) {
            log.error("Library scan failed for {}: {}", libraryRoot, e.getMessage(), e);
            scanRecord.setStatus("Failed");
            scanRecord.setErrorMessage(e.getMessage());
            scanRecord.setCompletedAt(OffsetDateTime.now());
            libraryScanRepository.save(scanRecord);
            return new ScanResult(0, 0, 0, 0, 1);
        }
    }

    public boolean isScanInProgress() {
        return scanInProgress.get();
    }

    public List<LibraryScanEntity> getRecentScans(int limit) {
        return libraryScanRepository.findRecentScans(limit);
    }
}
