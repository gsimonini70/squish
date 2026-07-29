package com.lucsartech.squish.http.dto;

public record HealthResponse(String status, String phase, String version, String buildNumber) {}
