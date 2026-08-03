package com.lucsartech.squish.http.dto;

import java.util.List;

public record ProfilesResponse(List<ProfileInfo> profiles, String activeProfile) {}
