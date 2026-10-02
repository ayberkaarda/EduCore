package com.educore.ipaccess;

/** How an IPv4 range is written: one address, an inclusive {@code start-end} range or a CIDR block. */
public enum IpRangeKind {
    STATIC,
    RANGE,
    CIDR
}
