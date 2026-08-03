package com.lucsartech.squish.http.dto;

public record SystemInfo(long memUsed,
                         long memTotal,
                         long memMax,
                         long memFree,
                         double memPercent,
                         int activeThreads,
                         double cpuPercent,
                         int cpuCores) {}
