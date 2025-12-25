package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SystemInfoResponse(
    @JsonProperty("ServerName") String serverName,
    @JsonProperty("Version") String version,
    @JsonProperty("ProductName") String productName,
    @JsonProperty("OperatingSystem") String operatingSystem,
    @JsonProperty("Id") String id,
    @JsonProperty("LocalAddress") String localAddress,
    @JsonProperty("WanAddress") String wanAddress,
    @JsonProperty("HasPendingRestart") boolean hasPendingRestart,
    @JsonProperty("HasUpdateAvailable") boolean hasUpdateAvailable,
    @JsonProperty("StartupWizardCompleted") Boolean startupWizardCompleted,
    @JsonProperty("SupportsLibraryMonitor") Boolean supportsLibraryMonitor,
    @JsonProperty("CanSelfRestart") Boolean canSelfRestart,
    @JsonProperty("CanLaunchWebBrowser") Boolean canLaunchWebBrowser,
    @JsonProperty("PackageName") String packageName,
    @JsonProperty("EncoderLocation") String encoderLocation
) {

    public static SystemInfoResponse publicInfo(
        String serverName,
        String version,
        String serverId,
        String localAddress
    ) {
        return new SystemInfoResponse(
            serverName,
            version,
            "Yaytsa",
            System.getProperty("os.name"),
            serverId,
            localAddress,
            null,
            false,
            false,
            true,
            true,
            false,
            false,
            "yaytsa-media-server",
            "System"
        );
    }

    public static SystemInfoResponse fullInfo(
        String serverName,
        String version,
        String serverId,
        String localAddress,
        String wanAddress
    ) {
        return new SystemInfoResponse(
            serverName,
            version,
            "Yaytsa",
            System.getProperty("os.name"),
            serverId,
            localAddress,
            wanAddress,
            false,
            false,
            true,
            true,
            false,
            false,
            "yaytsa-media-server",
            "System"
        );
    }
}
