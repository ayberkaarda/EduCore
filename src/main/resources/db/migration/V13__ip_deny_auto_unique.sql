-- P5: at most one automatic (source = 'AUTO') deny rule per address. IpAutoDenyService writes it with
-- INSERT ... ON CONFLICT DO UPDATE, so concurrent failed logins on several threads or instances extend the one
-- rule instead of adding duplicates. Expired AUTO rows are purged by IpDenyRuleCleanup; until then the upsert
-- simply renews them. MANUAL rules are not affected.

-- Keep only the AUTO row with the latest expiry per address (duplicates can only exist before this index).
DELETE FROM ip_deny_rule r
USING ip_deny_rule newer
WHERE r.source = 'AUTO'
  AND newer.source = 'AUTO'
  AND newer.start_ip = r.start_ip
  AND (newer.expires_at > r.expires_at OR (newer.expires_at = r.expires_at AND newer.id > r.id));

CREATE UNIQUE INDEX ux_ip_deny_rule_auto_address ON ip_deny_rule (start_ip) WHERE source = 'AUTO';
