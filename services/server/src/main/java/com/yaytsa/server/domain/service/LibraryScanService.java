package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.fs.FileSystemMediaScanner;
import com.yaytsa.server.infrastructure.fs.FileSystemMediaScanner.ScanResult;
import com.yaytsa.server.infrastructure.persistence.entity.LibraryScanEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ScanStatus;
import com.yaytsa.server.infrastructure.persistence.entity.ScanType;
import com.yaytsa.server.infrastructure.persistence.repository.LibraryScanRepository;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class LibraryScanService {

  private static final Logger log = LoggerFactory.getLogger(LibraryScanService.class);

  private final FileSystemMediaScanner scanner;
  private final LibraryScanRepository libraryScanRepository;
  private final LyricsFetchService lyricsFetchService;
  private final Executor taskExecutor;
  private final AtomicBoolean scanInProgress = new AtomicBoolean(false);

  @Value("${yaytsa.media.library.roots:./media}")
  private String libraryRoots;

  @Value("${yaytsa.media.library.scan-on-startup:true}")
  private boolean scanOnStartup;

  public LibraryScanService(
      FileSystemMediaScanner scanner,
      LibraryScanRepository libraryScanRepository,
      LyricsFetchService lyricsFetchService,
      @Qualifier("applicationTaskExecutor") Executor taskExecutor) {
    this.scanner = scanner;
    this.libraryScanRepository = libraryScanRepository;
    this.lyricsFetchService = lyricsFetchService;
    this.taskExecutor = taskExecutor;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    if (scanOnStartup) {
      log.info("Application ready, triggering startup library scan");
      triggerFullScan()
          .ifPresentOrElse(
              future -> log.info("Startup library scan initiated"),
              () -> log.warn("Startup library scan skipped - another scan already in progress"));
    }
  }

  public Optional<CompletableFuture<List<ScanResult>>> triggerFullScan() {
    if (!scanInProgress.compareAndSet(false, true)) {
      log.warn("Scan already in progress, skipping");
      return Optional.empty();
    }

    List<String> roots =
        Arrays.stream(libraryRoots.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();

    log.info("Starting full library scan for {} root(s): {}", roots.size(), roots);

    CompletableFuture<List<ScanResult>> future =
        CompletableFuture.supplyAsync(
                () -> roots.stream().map(this::scanLibraryRoot).toList(), taskExecutor)
            .whenComplete(
                (results, ex) -> {
                  scanInProgress.set(false);
                  if (ex != null) {
                    log.error("Full library scan failed", ex);
                  } else {
                    log.info("Full library scan completed");
                    if (lyricsFetchService.isFetchOnScanEnabled()) {
                      CompletableFuture.runAsync(
                          () -> lyricsFetchService.fetchMissingLyrics(), taskExecutor);
                    }
                  }
                });

    return Optional.of(future);
  }

  public ScanResult scanLibraryRoot(String libraryRoot) {
    LibraryScanEntity scanRecord = new LibraryScanEntity();
    scanRecord.setLibraryRoot(libraryRoot);
    scanRecord.setScanType(ScanType.Full);
    scanRecord.setStatus(ScanStatus.Running);
    scanRecord.setStartedAt(OffsetDateTime.now());
    scanRecord = libraryScanRepository.save(scanRecord);

    try {
      ScanResult result = scanner.scan(libraryRoot);

      scanRecord.setFilesScanned(result.filesScanned());
      scanRecord.setFilesAdded(result.filesAdded());
      scanRecord.setFilesUpdated(result.filesUpdated());
      scanRecord.setFilesRemoved(result.filesRemoved());
      scanRecord.setErrorCount(result.errors());
      scanRecord.setStatus(ScanStatus.Completed);
      scanRecord.setCompletedAt(OffsetDateTime.now());
      libraryScanRepository.save(scanRecord);

      return result;

    } catch (Exception e) {
      log.error("Library scan failed for {}: {}", libraryRoot, e.getMessage(), e);
      scanRecord.setStatus(ScanStatus.Failed);
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
