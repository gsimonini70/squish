package com.lucsartech.squish.http.dto;

import java.util.Map;

public record ConfigResponse(String activeProfile,
                             boolean dryRun,
                             Map<String, ProfileDetailInfo> profiles,
                             String legacyMode,
                             boolean watchdogEnabled) {}
