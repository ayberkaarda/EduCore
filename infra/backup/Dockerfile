# EduCore backup sidecar: pg_dump (custom format) + gzip on a cron schedule (supercronic),
# local retention (14 daily, 8 weekly), optional S3-compatible upload (rclone), a dated copy of the
# erasure ledger and, only with BACKUP_ARCHIVE_CSV=true, archival of csv_uploads/done.
# See docs/ops/BACKUP_RESTORE.md.
#
# The PostgreSQL major version of the client must be >= the server's (compose runs postgres:15).
# Base image pinned by digest (postgres:15-alpine); Dependabot proposes digest updates weekly.
FROM postgres:15-alpine@sha256:f7d23353e1b15400d22ebe31189f4d314b87a4c129cc400c8c2d8d4ca127bf81

# supercronic and rclone come from Alpine packages (rebuilt by Alpine with a current Go toolchain).
# gosu is removed: the base image ships it for its root-to-postgres step-down in docker-entrypoint.sh,
# which this sidecar never runs (USER postgres, own entrypoint). gosu 1.19 (latest upstream release,
# built with go1.24.6) carries Go standard-library CVEs that fail the image scan (docs/ops/CI.md).
RUN rm -f /usr/local/bin/gosu \
    && apk add --no-cache supercronic rclone \
    && mkdir -p /backups/daily /backups/weekly /backups/csv-done /backups/ledger /backups/metrics \
    && chown -R postgres:postgres /backups \
    && chmod 0700 /backups/daily /backups/weekly /backups/csv-done /backups/ledger \
    && chmod 0755 /backups /backups/metrics

COPY --chmod=0755 bin/ /usr/local/bin/

# uid/gid 70 (postgres) from the base image; the job never needs root.
USER postgres
WORKDIR /backups
VOLUME ["/backups"]

# Unhealthy when the last complete backup is older than BACKUP_MAX_AGE_HOURS (default 26).
HEALTHCHECK --interval=5m --timeout=10s --start-period=1m --retries=1 CMD ["/usr/local/bin/backup-health.sh"]

ENTRYPOINT ["/usr/local/bin/backup-entrypoint.sh"]
CMD ["schedule"]
