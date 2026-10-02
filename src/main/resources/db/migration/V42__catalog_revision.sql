-- Revision of the public catalog: the moment of the last change that affected what anonymous visitors see
-- (a published course created, changed, unpublished or deleted, or a course published). CourseService
-- advances it in the same transaction as the change and never moves it backwards
-- (revised_at = greatest(revised_at, now())). site-facts dateModified and the sitemap lastmod of / and
-- /courses read it, so they move forward after an unpublish or delete instead of falling back to an older
-- course's updated_at.
CREATE TABLE catalog_revision (
    id smallint NOT NULL,
    revised_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_catalog_revision PRIMARY KEY (id),
    CONSTRAINT ck_catalog_revision_single_row CHECK (id = 1)
);

INSERT INTO catalog_revision (id, revised_at)
SELECT 1, COALESCE((SELECT max(updated_at) FROM course WHERE published), now());
