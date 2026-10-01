package com.educore.ipaccess;

/** How an IP allocation rule is written: one address, an inclusive {@code start-end} range or a CIDR block. */
public enum IpRuleType {
    STATIC,
    RANGE,
    CIDR
}
