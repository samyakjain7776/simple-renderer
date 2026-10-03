package com.samyak.simplerenderer.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "renderer")
public class RendererProperties {
  private Path stagingDirectory = Path.of(System.getProperty("java.io.tmpdir"), "simple-renderer");
  private long maxInputBytes = 100L * 1024 * 1024;
  private long maxOutputBytes = 100L * 1024 * 1024;
  private long maxRows = 1_000_000L;
  private int maxConcurrentReports = 2;
  private int workerThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
  private int chunkSize = 500;
  private int maxChunkCharacters = 256 * 1024;
  private int maxInFlightChunks = 4;

  public Path getStagingDirectory() {
    return stagingDirectory;
  }

  public void setStagingDirectory(Path stagingDirectory) {
    this.stagingDirectory = stagingDirectory;
  }

  public long getMaxInputBytes() {
    return maxInputBytes;
  }

  public void setMaxInputBytes(long maxInputBytes) {
    this.maxInputBytes = maxInputBytes;
  }

  public long getMaxOutputBytes() {
    return maxOutputBytes;
  }

  public void setMaxOutputBytes(long maxOutputBytes) {
    this.maxOutputBytes = maxOutputBytes;
  }

  public long getMaxRows() {
    return maxRows;
  }

  public void setMaxRows(long maxRows) {
    this.maxRows = maxRows;
  }

  public int getMaxConcurrentReports() {
    return maxConcurrentReports;
  }

  public void setMaxConcurrentReports(int maxConcurrentReports) {
    this.maxConcurrentReports = maxConcurrentReports;
  }

  public int getWorkerThreads() {
    return workerThreads;
  }

  public void setWorkerThreads(int workerThreads) {
    this.workerThreads = workerThreads;
  }

  public int getChunkSize() {
    return chunkSize;
  }

  public void setChunkSize(int chunkSize) {
    this.chunkSize = chunkSize;
  }

  public int getMaxChunkCharacters() {
    return maxChunkCharacters;
  }

  public void setMaxChunkCharacters(int value) {
    maxChunkCharacters = value;
  }

  public int getMaxInFlightChunks() {
    return maxInFlightChunks;
  }

  public void setMaxInFlightChunks(int maxInFlightChunks) {
    this.maxInFlightChunks = maxInFlightChunks;
  }
}
