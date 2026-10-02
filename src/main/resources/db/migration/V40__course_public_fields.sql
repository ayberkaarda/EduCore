-- Public course catalog fields (anonymous read-only API under /api/v1/public/** and /sitemap.xml).
--   slug         URL key of a published course: lowercase ASCII letters and digits in hyphen-separated
--                groups, at most 80 characters, unique. NULL is allowed for courses that were never
--                given one (rows imported by CSV); a published course always has one (ck_course_published_slug).
--   description  Plain-text summary shown on the public site, at most 1000 characters.
--   published    Only published courses are visible anonymously; every existing course starts unpublished.
--   updated_at   Last change of the row; drives sitemap lastmod and site-facts dateModified. The application
--                sets it on every insert and update (Hibernate @UpdateTimestamp); the default covers rows
--                written outside the application.

ALTER TABLE course ADD COLUMN slug varchar(80);
ALTER TABLE course ADD COLUMN description varchar(1000);
ALTER TABLE course ADD COLUMN published boolean DEFAULT false NOT NULL;
ALTER TABLE course ADD COLUMN updated_at timestamp(6) with time zone DEFAULT now() NOT NULL;

-- Deterministic, collision-safe backfill: rows are processed in id order; the stem is the name
-- transliterated (Turkish letters), lowercased, every run of other characters replaced by one hyphen,
-- cut to 50 characters and trimmed of hyphens ('course' when nothing is left). The first row with a stem
-- takes it as is; a later row whose stem is already taken gets the first free ordinal suffix '-2', '-3', ...
-- (the same rule as CourseSlugs.unique in the application). Primary keys never appear in a slug.
DO $$
DECLARE
    r record;
    stem text;
    candidate text;
    ordinal integer;
BEGIN
    FOR r IN SELECT id, name FROM course ORDER BY id LOOP
        stem := lower(translate(r.name, 'ÇĞİIÖŞÜÂÎÛçğıöşüâîû', 'CGIIOSUAIUcgiosuaiu'));
        stem := regexp_replace(stem, '[^a-z0-9]+', '-', 'g');
        stem := trim(BOTH '-' FROM left(trim(BOTH '-' FROM stem), 50));
        IF stem = '' THEN
            stem := 'course';
        END IF;
        candidate := stem;
        ordinal := 1;
        WHILE EXISTS (SELECT 1 FROM course WHERE slug = candidate) LOOP
            ordinal := ordinal + 1;
            candidate := stem || '-' || ordinal;
        END LOOP;
        UPDATE course SET slug = candidate WHERE id = r.id;
    END LOOP;
END
$$;

ALTER TABLE course ADD CONSTRAINT uk_course_slug UNIQUE (slug);
ALTER TABLE course ADD CONSTRAINT ck_course_slug_format
    CHECK (slug IS NULL OR (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(slug) <= 80));
ALTER TABLE course ADD CONSTRAINT ck_course_published_slug CHECK (NOT published OR slug IS NOT NULL);

-- Public listing filters on published and orders by name or updated_at.
CREATE INDEX ix_course_published_updated_at ON course (published, updated_at);
