package com.educore.ipaccess;

/** Who created a deny rule: an ADMIN through the API, or the automatic failed-login rule. */
public enum IpDenyRuleSource {
    MANUAL,
    AUTO
}
