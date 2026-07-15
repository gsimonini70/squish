-- ============================================
-- Squish - Tracking Table DDL (Oracle 11g compatible)
-- ============================================
-- Stores compression history to avoid re-processing.
-- Run this script once, before the first execution, against the schema that owns
-- the master/detail tables.
--
-- Usage: sqlplus user/pass@db @create_tracking_table.sql
--
-- This script is IDEMPOTENT: re-running it on a schema that is already set up
-- is a no-op and exits cleanly. ("CREATE TABLE" alone would abort with
-- ORA-00955 on the first object that already exists, leaving a half-created
-- schema behind and no way to finish the job.)
--
-- GRAIN: one tracking row per PART, not per document.
-- The detail table has the composite PK (OTTI_ID, OTTI_CTR): one master document
-- (OTTICA.OTT_ID) can own N detail parts, each with its own PDF BLOB. The tracking
-- table therefore mirrors that key exactly - (OTT_ID, OTT_CTR) - because the Java
-- resume anti-join is
--
--     NOT EXISTS (SELECT 1 FROM SQUISH_PROCESSED SP
--                  WHERE SP.OTT_ID = OTTICAI.OTTI_ID AND SP.OTT_CTR = OTTICAI.OTTI_CTR)
--
-- and it must be able to say "part 3 of document 42 is still to do" without
-- excluding parts 1 and 2. Keying tracking on OTT_ID alone (as Squish did before
-- the composite-key release) meant a run interrupted after part 1 committed excluded
-- the WHOLE document from every later run: parts 2..N were never compressed, silently,
-- while tracking reported SUCCESS. If you are upgrading such an install, run
-- migrate_tracking_composite.sql - do NOT just drop and recreate this table, or every
-- already-compressed PDF gets compressed a second time (Squish is lossy).
--
-- The names OTT_ID and OTT_CTR are hardcoded in the Java (the NOT EXISTS anti-join
-- and every MERGE): squish.query.id-column / detail-ctr-column rename the columns of
-- the SOURCE tables, NOT these.
--
-- The writers still use MERGE rather than INSERT - not because of a key asymmetry
-- (there is none now) but because a re-run over a record whose tracking row already
-- exists must update it in place instead of raising ORA-00001.
-- ============================================

SET SERVEROUTPUT ON
WHENEVER SQLERROR EXIT FAILURE

-- Helper: run a DDL statement, tolerating "already exists".
--   ORA-00955: name is already used by an existing object
--   ORA-01408: such column list already indexed
--   ORA-00942 / ORA-01418 are NOT tolerated - they mean something is really wrong.
DECLARE
    PROCEDURE ddl(p_sql IN VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
        DBMS_OUTPUT.PUT_LINE('  created: ' || SUBSTR(p_sql, 1, 60));
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE IN (-955, -1408) THEN
                DBMS_OUTPUT.PUT_LINE('  exists : ' || SUBSTR(p_sql, 1, 60));
            ELSE
                RAISE;
            END IF;
    END;
BEGIN
    ddl('CREATE SEQUENCE SQUISH_PROCESSED_SEQ START WITH 1 INCREMENT BY 1 NOCACHE');

    -- UQ_SQUISH_PROCESSED is NAMED on purpose. Its implicit unique index - leading
    -- column OTT_ID - is the index the resume anti-join probes once per detail row,
    -- so it is the single most performance-critical object in this script. Naming it
    -- also means a future migration can find and drop it without guessing at a
    -- SYS_Cnnnnnn name (the old OTT_ID-only constraint was system-named, which is
    -- exactly the mess migrate_tracking_composite.sql has to clean up).
    ddl('CREATE TABLE SQUISH_PROCESSED (
            ID              NUMBER PRIMARY KEY,
            OTT_ID          NUMBER NOT NULL,
            OTT_CTR         NUMBER NOT NULL,
            ORIGINAL_SIZE   NUMBER NOT NULL,
            COMPRESSED_SIZE NUMBER,
            SAVINGS_PERCENT NUMBER(5,2),
            STATUS          VARCHAR2(20) NOT NULL,
            ERROR_MESSAGE   VARCHAR2(500),
            PROCESSED_DATE  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
            HOSTNAME        VARCHAR2(100),
            CONSTRAINT UQ_SQUISH_PROCESSED UNIQUE (OTT_ID, OTT_CTR)
         )');

    ddl('CREATE INDEX IDX_SQUISH_PROCESSED_DATE ON SQUISH_PROCESSED(PROCESSED_DATE)');
    ddl('CREATE INDEX IDX_SQUISH_STATUS ON SQUISH_PROCESSED(STATUS)');

    -- No index on OTT_ID alone: UQ_SQUISH_PROCESSED's index already leads with it,
    -- so "all the parts of document 42" is an index range scan on that same index.
END;
/

-- Auto-increment ID (11g has no IDENTITY column).
-- CREATE OR REPLACE is idempotent on its own.
-- The Java never supplies ID - every MERGE's INSERT branch lists the business columns
-- only - so this trigger is what makes those inserts work. If it is dropped or
-- disabled, Squish fails with ORA-01400 (cannot insert NULL into ID) on its first write.
CREATE OR REPLACE TRIGGER SQUISH_PROCESSED_TRG
BEFORE INSERT ON SQUISH_PROCESSED
FOR EACH ROW
BEGIN
    IF :NEW.ID IS NULL THEN
        SELECT SQUISH_PROCESSED_SEQ.NEXTVAL INTO :NEW.ID FROM DUAL;
    END IF;
END;
/

COMMENT ON TABLE SQUISH_PROCESSED IS 'Squish PDF compression tracking table - one row per detail PART (OTT_ID, OTT_CTR), not per document';

COMMENT ON COLUMN SQUISH_PROCESSED.OTT_ID IS 'Reference to OTTICA.OTT_ID / OTTICAI.OTTI_ID (composite key part 1)';
COMMENT ON COLUMN SQUISH_PROCESSED.OTT_CTR IS 'Reference to OTTICAI.OTTI_CTR (composite key part 2) - which PART of the document this row tracks';

-- SUCCESS / SKIPPED / ERROR are the exact three values the Java writes
-- (CompressionPipeline / WatchdogService). Keep them in sync if CompressionResult
-- ever grows a variant.
-- MIGRATED is never written by the Java: only migrate_tracking_composite.sql writes it,
-- for parts it ASSUMED were already compressed by an older install. The resume
-- anti-join tests row existence and ignores STATUS, so a MIGRATED row blocks
-- re-compression exactly like a SUCCESS one - it just does not pollute the real
-- SUCCESS totals with sizes nobody ever measured.
COMMENT ON COLUMN SQUISH_PROCESSED.STATUS IS 'SUCCESS=compressed, SKIPPED=not a PDF or no gain, ERROR=failed, MIGRATED=assumed done by migrate_tracking_composite.sql';

-- RECORD_COUNT counts PARTS, because that is the grain of the table. A multi-part
-- document contributes one row per part, so RECORD_COUNT > DOCUMENT_COUNT wherever
-- multi-part documents exist. Both are reported: "how many PDFs did we compress"
-- is RECORD_COUNT, "how many documents did we touch" is DOCUMENT_COUNT, and reading
-- one as the other is the whole reason the old per-document bug went unnoticed for so long.
-- The byte totals are per part and therefore already correct as sums.
--
-- The view is a CONVENIENCE, not a dependency: Squish never reads it. So a missing
-- CREATE VIEW privilege must NOT abort this script - the tracking table, the sequence
-- and the trigger are already in place by now, and the application is fully functional
-- without SQUISH_STATS. (A bare "CREATE OR REPLACE VIEW" here would hit ORA-01031 under
-- WHENEVER SQLERROR EXIT FAILURE and leave a setup that is complete but reported as
-- failed - and a least-privilege Squish account is exactly the case where that happens,
-- since create_squish_user.sql revokes CREATE VIEW once setup is done.)
DECLARE
    e_no_privilege EXCEPTION;
    PRAGMA EXCEPTION_INIT(e_no_privilege, -1031);   -- ORA-01031: insufficient privileges
BEGIN
    EXECUTE IMMEDIATE q'[
        CREATE OR REPLACE VIEW SQUISH_STATS AS
        SELECT
            STATUS,
            COUNT(*) AS RECORD_COUNT,
            COUNT(DISTINCT OTT_ID) AS DOCUMENT_COUNT,
            ROUND(SUM(ORIGINAL_SIZE) / 1024 / 1024, 2) AS ORIGINAL_MB,
            ROUND(SUM(COMPRESSED_SIZE) / 1024 / 1024, 2) AS COMPRESSED_MB,
            ROUND(AVG(SAVINGS_PERCENT), 2) AS AVG_SAVINGS_PCT,
            MIN(PROCESSED_DATE) AS FIRST_PROCESSED,
            MAX(PROCESSED_DATE) AS LAST_PROCESSED
        FROM SQUISH_PROCESSED
        GROUP BY STATUS
    ]';
    DBMS_OUTPUT.PUT_LINE('  OK      : view SQUISH_STATS');
EXCEPTION
    WHEN e_no_privilege THEN
        DBMS_OUTPUT.PUT_LINE('  SKIPPED : view SQUISH_STATS - no CREATE VIEW privilege.');
        DBMS_OUTPUT.PUT_LINE('            This is NOT an error. Squish does not use the view;');
        DBMS_OUTPUT.PUT_LINE('            it only exists so a human can read the stats. To add it');
        DBMS_OUTPUT.PUT_LINE('            later: GRANT CREATE VIEW, then re-run this script.');
END;
/

COMMIT;

SELECT 'SQUISH_PROCESSED is ready (' || COUNT(*) || ' rows)' AS STATUS
FROM SQUISH_PROCESSED;

EXIT SUCCESS
