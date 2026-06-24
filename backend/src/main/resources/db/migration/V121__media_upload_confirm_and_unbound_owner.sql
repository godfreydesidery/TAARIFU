-- =============================================================================
-- V121__media_upload_confirm_and_unbound_owner.sql
--   Media attachment pipeline: presigned-upload -> confirm -> bind-to-report.
--   (media module, PRD §21 EI-8, §15, §18; ARCHITECTURE.md §4.1.)
--
-- Responsibility: evolve the existing `media_object` table (V47) to support the
-- citizen evidence-attachment pipeline, where a photo is uploaded BEFORE the host
-- report exists and is bound to it at file time. ADDITIVE and forward-only; the
-- base table already exists, so we ALTER (never recreate). MUST match the updated
-- JPA entity exactly so Hibernate ddl-auto=validate passes (ARCHITECTURE §4.1).
--
-- (1) `uploaded` (NEW) — the confirm-step flag. The pre-signed PUT happens directly
--     client<->store, so the app never observes the upload finishing. The explicit
--     `confirm` call asserts "the bytes are there"; at that point the content-type
--     allow-list (images only) and max size are enforced and the object becomes
--     scan-eligible. A never-confirmed object is a dangling upload-intent and must
--     never be referenced by a report or served. NOT NULL DEFAULT FALSE.
--
-- (2) `owner_id` made NULLABLE — bind-later. The photo is uploaded before the report
--     is filed, so the host id is unknown at upload time. The object is created
--     UNBOUND (owner_id NULL) and the reporting module binds it to the report's
--     public id at file time via the media.api port. V47 created this column NOT
--     NULL; we relax it. owner_id remains a BY-ID reference (NOT a real FK — the host
--     lives in another module; ARCHITECTURE §3.2), so relaxing nullability is safe.
--
-- Forward-only; never edit once applied — add a new migration.
-- =============================================================================

ALTER TABLE media_object
    ADD COLUMN uploaded BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN media_object.uploaded IS
    'TRUE once the client confirmed the bytes were PUT and the declared content-type/size passed policy '
    || '(confirm step, V121); a never-confirmed object is a dangling upload-intent - never bound or served.';

ALTER TABLE media_object
    ALTER COLUMN owner_id DROP NOT NULL;

COMMENT ON COLUMN media_object.owner_id IS
    'Host resource public id; NULL until the host module binds the object at file time (bind-later, V121). '
    || 'Referenced BY ID only (host lives in another module - ARCHITECTURE §3.2), so deliberately NOT a real FK.';
