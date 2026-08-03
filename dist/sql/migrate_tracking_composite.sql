-- ============================================
-- Squish - Migrate SQUISH_PROCESSED to the composite key (Oracle 11g compatible)
-- ============================================
-- Upgrades an EXISTING, POPULATED tracking table from the old per-document shape
--
--     OTT_ID NUMBER NOT NULL UNIQUE            -- one row per document
--
-- to the per-part shape that the composite-key release of Squish requires
--
--     OTT_ID  NUMBER NOT NULL
--     OTT_CTR NUMBER NOT NULL
--     CONSTRAINT UQ_SQUISH_PROCESSED UNIQUE (OTT_ID, OTT_CTR)
--
-- Fresh installs do NOT need this script: create_tracking_table.sql already builds
-- the new shape. Run this ONLY on a site whose SQUISH_PROCESSED still has no OTT_CTR
-- column - i.e. one that has been running a pre-composite-key Squish.
--
-- Usage: sqlplus squish/pass@db @migrate_tracking_composite.sql
--
-- ---------------------------------------------------------------------------
-- WHY: the bug being fixed
-- ---------------------------------------------------------------------------
-- The detail table has the composite PK (OTTI_ID, OTTI_CTR): one document can own
-- N parts, each with its own PDF BLOB. The old tracking table recorded only OTT_ID,
-- so the resume anti-join could only ask "was this DOCUMENT touched?". Once part 1
-- of a document committed, the document was excluded from every later run and
-- parts 2..N were NEVER compressed - silently, while tracking said SUCCESS.
--
-- ---------------------------------------------------------------------------
-- THE BACKFILL, AND WHAT IT ASSUMES - read this before running
-- ---------------------------------------------------------------------------
-- An existing tracking row proves that SOME part of that OTT_ID was processed.
-- It does NOT record WHICH part. That information was never written down and is
-- gone. Any migration must therefore GUESS, and the two ways of being wrong are
-- not remotely symmetric:
--
--   * Guess "already done" when it was not
--         -> the part stays uncompressed. A missed saving. Nothing is damaged, and
--            it can be recovered at any time (see RECOVERY below).
--
--   * Guess "not done" when it actually was
--         -> Squish compresses the PDF a SECOND time. Squish is LOSSY: it downscales
--            images and re-encodes JPEG. A second pass permanently destroys image
--            quality in a document that is already in its final, compressed state.
--            This is REAL, IRREVERSIBLE DATA DAMAGE.
--
-- So this script deliberately errs toward "already done". For each existing tracking
-- row it expands that row into ONE ROW PER OTTI_CTR that currently exists in the
-- detail table for that OTT_ID, and marks them all as processed. The parts we cannot
-- prove were compressed are assumed compressed.
--
-- Consequence, stated plainly: parts 2..N of the multi-part documents that the old
-- code skipped will STAY uncompressed. This script does not go back and fix them.
-- It makes the tracking table honest about its own grain, and it protects the PDFs
-- you already have. It buys correctness for every FUTURE run - nothing more.
--
-- The synthesized rows are written with STATUS = 'MIGRATED', never 'SUCCESS'. They
-- carry no invented byte counts, so SQUISH_STATS keeps reporting the real numbers
-- for the work that really happened, and any DBA can see exactly which rows are
-- assumptions:
--
--     SELECT * FROM SQUISH_PROCESSED WHERE STATUS = 'MIGRATED';
--
-- RECOVERY (only if you accept the double-compression risk): deleting a MIGRATED row
-- puts that part back in scope for the next run. Do this only for documents you can
-- prove were never compressed - e.g. by checking the PDF's own producer metadata -
-- and never in bulk.
--
-- ---------------------------------------------------------------------------
-- BEFORE YOU RUN
-- ---------------------------------------------------------------------------
--   1. STOP SQUISH. Not "pause the batch" - stop it, including watchdog mode.
--      Between step 2 and step 7 this script deliberately leaves the tracking table
--      with NO unique constraint (the old UNIQUE(OTT_ID) has to go before the
--      expansion can insert a second row for the same document). A Squish writing
--      into that window could insert duplicates that make step 7 fail.
--   2. BACK UP SQUISH_PROCESSED. It is small:
--         CREATE TABLE SQUISH_PROCESSED_BAK AS SELECT * FROM SQUISH_PROCESSED;
--   3. Run this as the OWNER of the tracking table (the Squish user), with SELECT on
--      the detail table. If the schema was hardened per section 4 of
--      create_squish_user.sql, the ALTER TABLE ... ADD CONSTRAINT in step 7 builds an
--      index and may need CREATE TABLE re-granted for the duration of the migration:
--         GRANT CREATE TABLE TO SQUISH;    -- as DBA, revoke again afterwards
--   4. Deploy the new Squish jar only AFTER this script has completed successfully.
--      Old jar + new table = ORA-01400 (NULL OTT_CTR) on the first write; new jar +
--      old table = ORA-00904 (invalid identifier OTT_CTR). Neither corrupts anything,
--      but neither runs.
--
-- The script is IDEMPOTENT and safe to re-run: every step tests the state it is about
-- to change, and the whole backfill (step 3 + step 4) is ONE transaction with ONE
-- COMMIT. It FAILS LOUDLY - WHENEVER SQLERROR EXIT FAILURE, plus explicit assertions
-- between the steps - rather than half-applying and reporting success.
-- ============================================

-- --------------------------------------------
-- EDIT THESE if your squish.query.* config does not use the shipped defaults
-- --------------------------------------------
DEFINE tracking_table   = SQUISH_PROCESSED
DEFINE tracking_seq     = SQUISH_PROCESSED_SEQ
DEFINE tracking_trg     = SQUISH_PROCESSED_TRG
DEFINE detail_table     = OTTICAI       -- squish.query.detail-table
DEFINE detail_id_col    = OTTI_ID       -- squish.query.detail-id-column
DEFINE detail_ctr_col   = OTTI_CTR      -- squish.query.detail-ctr-column
DEFINE orphan_ctr       = 0             -- see step 3b

SET SERVEROUTPUT ON SIZE UNLIMITED
SET VERIFY OFF
WHENEVER SQLERROR EXIT FAILURE

PROMPT
PROMPT ============================================
PROMPT Squish tracking migration: OTT_ID -> (OTT_ID, OTT_CTR)
PROMPT ============================================

-- ============================================
-- STEP 0 - pre-flight
-- ============================================
-- Everything that would otherwise blow up in the middle of the migration with a
-- cryptic error is checked here, while the table is still untouched.
DECLARE
    v_count     NUMBER;
    v_status    VARCHAR2(30);
    v_last_num  NUMBER;
    v_max_id    NUMBER;
BEGIN
    DBMS_OUTPUT.PUT_LINE('--- Step 0: pre-flight ---');

    -- The tracking table must exist. If it does not, this is a fresh install:
    -- run create_tracking_table.sql instead, not this script.
    SELECT COUNT(*) INTO v_count FROM user_tables
     WHERE table_name = UPPER('&tracking_table');
    IF v_count = 0 THEN
        raise_application_error(-20001,
            '&tracking_table does not exist. This is a fresh install: '
            || 'run create_tracking_table.sql instead.');
    END IF;

    -- The detail table must be READABLE - the whole backfill is driven off it. Test the
    -- capability, not the name: this way it works whether &detail_table is a local table,
    -- a synonym, or a schema-qualified MC_CONSCR.OTTICAI, and it also catches the case
    -- where the object exists but the SELECT grant is missing.
    BEGIN
        EXECUTE IMMEDIATE
            'SELECT COUNT(*) FROM &detail_table WHERE ROWNUM = 1' INTO v_count;
    EXCEPTION
        WHEN OTHERS THEN
            raise_application_error(-20002,
                'Cannot read the detail table &detail_table: ' || SQLERRM
                || ' - check the DEFINEs at the top of this script, and the SELECT '
                || 'grant / synonym.');
    END;

    -- The backfill INSERT does not supply ID: it relies on the BEFORE INSERT trigger
    -- to draw one from the sequence, exactly as the Java does. Doing it any other way
    -- (e.g. MAX(ID)+ROWNUM) would leave the sequence behind the data and hand the next
    -- production run an ORA-00001 on the primary key. So the trigger must be there and
    -- must be enabled.
    BEGIN
        SELECT status INTO v_status FROM user_triggers
         WHERE trigger_name = UPPER('&tracking_trg');
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            raise_application_error(-20003,
                'Trigger &tracking_trg is missing. Re-create it from '
                || 'create_tracking_table.sql before migrating.');
    END;
    IF v_status <> 'ENABLED' THEN
        raise_application_error(-20004,
            'Trigger &tracking_trg exists but is ' || v_status
            || '. Enable it: ALTER TRIGGER &tracking_trg ENABLE');
    END IF;

    -- ...and the sequence must be AHEAD of the data. If someone ever loaded rows with
    -- explicit IDs, the sequence can lag MAX(ID), and every backfill insert would then
    -- collide on the primary key. Catch it now, with a message that says what to do.
    -- (LAST_NUMBER is the next value the sequence will issue - exact under NOCACHE,
    -- an upper bound under CACHE - so "<= MAX(ID)" means a collision is certain.)
    BEGIN
        SELECT last_number INTO v_last_num FROM user_sequences
         WHERE sequence_name = UPPER('&tracking_seq');
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            raise_application_error(-20005,
                'Sequence &tracking_seq is missing. Re-create it from '
                || 'create_tracking_table.sql before migrating.');
    END;
    EXECUTE IMMEDIATE 'SELECT NVL(MAX(ID), 0) FROM &tracking_table' INTO v_max_id;
    IF v_last_num <= v_max_id THEN
        raise_application_error(-20006,
            'Sequence &tracking_seq (next value ' || v_last_num || ') is behind '
            || 'MAX(ID) = ' || v_max_id || ' in &tracking_table. The backfill would '
            || 'collide on the primary key. Advance the sequence past MAX(ID) first.');
    END IF;

    EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM &tracking_table' INTO v_count;
    DBMS_OUTPUT.PUT_LINE('  OK: &tracking_table has ' || v_count || ' row(s), '
        || 'trigger ENABLED, sequence at ' || v_last_num || ' (MAX(ID) = ' || v_max_id || ')');
END;
/

-- ============================================
-- STEP 1 - add OTT_CTR, NULLable for now
-- ============================================
-- NULLable, because the existing rows have no value for it yet. It is tightened to
-- NOT NULL in step 6, once every row has been backfilled and verified.
BEGIN
    DBMS_OUTPUT.PUT_LINE('--- Step 1: add OTT_CTR ---');
    EXECUTE IMMEDIATE 'ALTER TABLE &tracking_table ADD (OTT_CTR NUMBER)';
    DBMS_OUTPUT.PUT_LINE('  added: OTT_CTR NUMBER (nullable)');
EXCEPTION
    WHEN OTHERS THEN
        -- ORA-01430: column being added already exists in table -> a previous run of
        -- this script already got this far. Anything else is real.
        IF SQLCODE = -1430 THEN
            DBMS_OUTPUT.PUT_LINE('  exists: OTT_CTR (previous run) - continuing');
        ELSE
            RAISE;
        END IF;
END;
/

-- ============================================
-- STEP 2 - drop the old UNIQUE (OTT_ID)
-- ============================================
-- This MUST happen before the backfill: the expansion in step 4 inserts a second,
-- third, ... row for the same OTT_ID, and the old constraint exists precisely to
-- forbid that (it is the ORA-00001 that production used to hit).
--
-- The constraint was declared inline - "OTT_ID NUMBER NOT NULL UNIQUE" - so Oracle
-- named it SYS_Cnnnnnn and the number differs at every site. Never hardcode it: find
-- it by shape - a UNIQUE constraint on this table over exactly one column, OTT_ID.
-- Dropping it drops the implicit index Oracle built for it.
--
-- The separate NOT NULL check constraint on OTT_ID is untouched, and should be:
-- OTT_ID stays mandatory.
DECLARE
    v_dropped NUMBER := 0;
BEGIN
    DBMS_OUTPUT.PUT_LINE('--- Step 2: drop old UNIQUE(OTT_ID) ---');

    FOR c IN (
        SELECT uc.constraint_name
          FROM user_constraints uc
         WHERE uc.table_name      = UPPER('&tracking_table')
           AND uc.constraint_type = 'U'
           AND 1 = (SELECT COUNT(*)
                      FROM user_cons_columns ucc
                     WHERE ucc.owner           = uc.owner
                       AND ucc.constraint_name = uc.constraint_name)
           AND 'OTT_ID' = (SELECT MAX(ucc.column_name)
                             FROM user_cons_columns ucc
                            WHERE ucc.owner           = uc.owner
                              AND ucc.constraint_name = uc.constraint_name)
    ) LOOP
        EXECUTE IMMEDIATE 'ALTER TABLE &tracking_table DROP CONSTRAINT ' || c.constraint_name;
        DBMS_OUTPUT.PUT_LINE('  dropped: ' || c.constraint_name || ' (was UNIQUE on OTT_ID alone)');
        v_dropped := v_dropped + 1;
    END LOOP;

    IF v_dropped = 0 THEN
        -- Already gone: either a previous run of this script dropped it, or this site
        -- never had it. Either way there is nothing to do - not an error.
        DBMS_OUTPUT.PUT_LINE('  none found - already migrated, or never present');
    END IF;
END;
/

-- ============================================
-- STEPS 3 + 4 - the backfill (ONE transaction)
-- ============================================
-- Note the order: 3a assigns a real OTT_CTR to each existing row, THEN 4 inserts the
-- siblings. Doing it the other way round would insert a row for EVERY ctr (the old
-- row's NULL OTT_CTR matches nothing in the anti-join) and then leave the old row to
-- be updated into a duplicate of one of them.
--
-- Both statements are re-runnable on their own (3a only touches NULLs, 4 only inserts
-- what is missing), and they share a single COMMIT: the backfill either lands whole
-- or not at all.
DECLARE
    v_updated  NUMBER;
    v_orphans  NUMBER;
    v_inserted NUMBER;
BEGIN
    DBMS_OUTPUT.PUT_LINE('--- Steps 3+4: backfill ---');

    -- 3a. Every existing row keeps its ID, its real byte counts and its real STATUS,
    --     and is pinned to the LOWEST part number that document actually has. Which
    --     part it truly described is unknowable (see the header); MIN is as good a
    --     representative as any, and it keeps the pre-migration SQUISH_STATS totals
    --     exactly as they were - no measured row is duplicated or invented.
    UPDATE &tracking_table SP
       SET OTT_CTR = (SELECT MIN(D.&detail_ctr_col)
                        FROM &detail_table D
                       WHERE D.&detail_id_col = SP.OTT_ID)
     WHERE SP.OTT_CTR IS NULL;
    v_updated := SQL%ROWCOUNT;
    DBMS_OUTPUT.PUT_LINE('  3a. pinned to MIN(ctr): ' || v_updated || ' row(s)');

    -- 3b. Orphans: a tracking row whose OTT_ID has no detail row at all any more
    --     (the document was deleted since it was compressed). The correlated subquery
    --     above returned NULL for those, and OTT_CTR must not stay NULL - step 6 would
    --     fail. Park them on &orphan_ctr.
    --     This cannot collide: the document has NO parts, so step 4 inserts nothing for
    --     it, and the old UNIQUE(OTT_ID) guarantees there was only ever one such row.
    --     The row is inert - with no detail row there is nothing for Squish to select -
    --     so this only preserves the history instead of discarding it.
    UPDATE &tracking_table
       SET OTT_CTR = &orphan_ctr
     WHERE OTT_CTR IS NULL;
    v_orphans := SQL%ROWCOUNT;
    IF v_orphans > 0 THEN
        DBMS_OUTPUT.PUT_LINE('  3b. orphans (no detail row left), parked on ctr=&orphan_ctr: '
            || v_orphans || ' row(s)');
    END IF;

    -- 4. The expansion. For every document that has a tracking row, mark EVERY part it
    --    currently has as processed - this is the "err toward already done" decision.
    --    ID is deliberately omitted: the BEFORE INSERT trigger draws it from the
    --    sequence, which keeps sequence and data in step.
    --    ORIGINAL_SIZE is NOT NULL, so it gets 0 - a size nobody measured is not going
    --    to be guessed at either. COMPRESSED_SIZE / SAVINGS_PERCENT stay NULL, and
    --    SUM()/AVG() ignore NULLs, so SQUISH_STATS stays truthful.
    --    The NOT EXISTS makes this re-runnable; it reads the table's consistent
    --    snapshot as of statement start, so it cannot see - or duplicate - the rows the
    --    same statement is inserting.
    --    The driving set is GROUPed to ONE ROW PER DOCUMENT, and that is not cosmetic:
    --    after a first successful run the tracking table holds N rows per document, so
    --    joining it to the detail table directly would emit N copies of any part that is
    --    still missing (e.g. a part added to a document since the migration) and the
    --    re-run would die on ORA-00001. Grouping first makes the statement produce at
    --    most one row per (OTT_ID, ctr), whatever state it is re-run in.
    INSERT INTO &tracking_table
        (OTT_ID, OTT_CTR, ORIGINAL_SIZE, COMPRESSED_SIZE, SAVINGS_PERCENT,
         STATUS, ERROR_MESSAGE, PROCESSED_DATE, HOSTNAME)
    SELECT SP.OTT_ID,
           D.&detail_ctr_col,
           0,
           NULL,
           NULL,
           'MIGRATED',
           'Assumed processed by migrate_tracking_composite.sql: the old tracking '
           || 'table did not record which part was compressed.',
           SP.PROCESSED_DATE,
           SP.HOSTNAME
      FROM (SELECT OTT_ID,
                   MIN(PROCESSED_DATE) AS PROCESSED_DATE,
                   MIN(HOSTNAME)       AS HOSTNAME
              FROM &tracking_table
             GROUP BY OTT_ID) SP
      JOIN &detail_table D
        ON D.&detail_id_col = SP.OTT_ID
     WHERE NOT EXISTS (SELECT 1
                         FROM &tracking_table X
                        WHERE X.OTT_ID  = SP.OTT_ID
                          AND X.OTT_CTR = D.&detail_ctr_col);
    v_inserted := SQL%ROWCOUNT;
    DBMS_OUTPUT.PUT_LINE('  4.  expanded into sibling parts (STATUS=MIGRATED): '
        || v_inserted || ' new row(s)');

    COMMIT;
    DBMS_OUTPUT.PUT_LINE('  committed');
END;
/

-- ============================================
-- STEP 5 - assert the backfill is complete
-- ============================================
-- Refuse to go on if a single OTT_CTR is still NULL. Without this, step 6 would fail
-- with a bare ORA-02296 and no clue as to which rows are to blame.
DECLARE
    v_nulls NUMBER;
    v_dupes NUMBER;
BEGIN
    DBMS_OUTPUT.PUT_LINE('--- Step 5: verify ---');

    EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM &tracking_table WHERE OTT_CTR IS NULL'
        INTO v_nulls;
    IF v_nulls > 0 THEN
        raise_application_error(-20007,
            v_nulls || ' row(s) still have a NULL OTT_CTR. The backfill did not '
            || 'complete. Inspect: SELECT * FROM &tracking_table WHERE OTT_CTR IS NULL');
    END IF;

    -- Duplicates would make step 7 fail with ORA-02299 half-way through, so name them
    -- here instead. There should be none: the old UNIQUE(OTT_ID) guaranteed one row per
    -- document going in, and the expansion only adds parts that were missing.
    EXECUTE IMMEDIATE '
        SELECT COUNT(*) FROM (
            SELECT OTT_ID, OTT_CTR FROM &tracking_table
             GROUP BY OTT_ID, OTT_CTR HAVING COUNT(*) > 1)'
        INTO v_dupes;
    IF v_dupes > 0 THEN
        raise_application_error(-20008,
            v_dupes || ' duplicate (OTT_ID, OTT_CTR) pair(s) found - the unique '
            || 'constraint cannot be created. Was Squish running during the migration? '
            || 'Inspect: SELECT OTT_ID, OTT_CTR, COUNT(*) FROM &tracking_table '
            || 'GROUP BY OTT_ID, OTT_CTR HAVING COUNT(*) > 1');
    END IF;

    DBMS_OUTPUT.PUT_LINE('  OK: no NULL OTT_CTR, no duplicate (OTT_ID, OTT_CTR)');
END;
/

-- ============================================
-- STEP 6 - OTT_CTR NOT NULL
-- ============================================
BEGIN
    DBMS_OUTPUT.PUT_LINE('--- Step 6: OTT_CTR NOT NULL ---');
    EXECUTE IMMEDIATE 'ALTER TABLE &tracking_table MODIFY (OTT_CTR NUMBER NOT NULL)';
    DBMS_OUTPUT.PUT_LINE('  OTT_CTR is now NOT NULL');
EXCEPTION
    WHEN OTHERS THEN
        -- ORA-01442: column to be modified to NOT NULL is already NOT NULL.
        IF SQLCODE = -1442 THEN
            DBMS_OUTPUT.PUT_LINE('  already NOT NULL (previous run) - continuing');
        ELSE
            RAISE;
        END IF;
END;
/

-- ============================================
-- STEP 7 - the new composite UNIQUE
-- ============================================
-- Named, unlike the constraint it replaces, so the next person who has to touch it can
-- find it. Its implicit index leads with OTT_ID, so it serves both the resume anti-join
-- (OTT_ID + OTT_CTR, an exact probe) and any "all the parts of document 42" lookup that
-- the dropped OTT_ID index used to serve. This is the index the anti-join lives on:
-- without it, every detail row triggers a full scan of the tracking table.
BEGIN
    DBMS_OUTPUT.PUT_LINE('--- Step 7: UNIQUE (OTT_ID, OTT_CTR) ---');
    EXECUTE IMMEDIATE 'ALTER TABLE &tracking_table
        ADD CONSTRAINT UQ_SQUISH_PROCESSED UNIQUE (OTT_ID, OTT_CTR)';
    DBMS_OUTPUT.PUT_LINE('  created: UQ_SQUISH_PROCESSED');
EXCEPTION
    WHEN OTHERS THEN
        -- ORA-00955: the name is taken. ORA-02261: an equivalent unique key already
        -- exists on those columns. Both mean a previous run already did this.
        IF SQLCODE IN (-955, -2261) THEN
            DBMS_OUTPUT.PUT_LINE('  exists (previous run) - continuing');
        ELSE
            RAISE;
        END IF;
END;
/

-- Bring the comments in line with create_tracking_table.sql, so a migrated schema and a
-- freshly created one are indistinguishable.
COMMENT ON TABLE &tracking_table IS 'Squish PDF compression tracking table - one row per detail PART (OTT_ID, OTT_CTR), not per document';
COMMENT ON COLUMN &tracking_table..OTT_ID IS 'Reference to OTTICA.OTT_ID / OTTICAI.OTTI_ID (composite key part 1)';
COMMENT ON COLUMN &tracking_table..OTT_CTR IS 'Reference to OTTICAI.OTTI_CTR (composite key part 2) - which PART of the document this row tracks';
COMMENT ON COLUMN &tracking_table..STATUS IS 'SUCCESS=compressed, SKIPPED=not a PDF or no gain, ERROR=failed, MIGRATED=assumed done by migrate_tracking_composite.sql';

-- The view now has a per-part grain and gains DOCUMENT_COUNT. Identical to the
-- definition shipped in create_tracking_table.sql.
--
-- It is tolerated to fail. By this point the TABLE has already been migrated and
-- committed, and Squish never reads the view - so a missing CREATE VIEW privilege must
-- not abort the script here. Aborting would report a completed migration as a failure
-- and send the operator hunting for a problem that does not exist. The stale view left
-- behind still works (it just lacks DOCUMENT_COUNT); the message below says so.
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
        FROM &tracking_table
        GROUP BY STATUS
    ]';
    DBMS_OUTPUT.PUT_LINE('  OK      : view SQUISH_STATS refreshed (now reports DOCUMENT_COUNT)');
EXCEPTION
    WHEN e_no_privilege THEN
        DBMS_OUTPUT.PUT_LINE('  SKIPPED : view SQUISH_STATS - no CREATE VIEW privilege.');
        DBMS_OUTPUT.PUT_LINE('            The MIGRATION ITSELF SUCCEEDED. Squish does not use the');
        DBMS_OUTPUT.PUT_LINE('            view; the old one still works, it just lacks DOCUMENT_COUNT.');
        DBMS_OUTPUT.PUT_LINE('            To refresh it: GRANT CREATE VIEW, then re-run this script.');
END;
/

COMMIT;

-- ============================================
-- STEP 8 - final invariant
-- ============================================
-- The point of the whole exercise: for every document with a tracking row, EVERY part
-- of it must now be tracked. If this count is not zero, some part of a
-- known-to-be-processed document is still in scope for the next run - which is exactly
-- the double-compression the header warns about. Fail rather than let it through.
--
-- This scans the detail table and can take a few minutes on a large one. It is a
-- read-only check: interrupting it changes nothing, and the script can simply be re-run.
DECLARE
    v_untracked NUMBER;
BEGIN
    DBMS_OUTPUT.PUT_LINE('--- Step 8: final invariant ---');

    SELECT COUNT(*) INTO v_untracked
      FROM &detail_table D
     WHERE EXISTS (SELECT 1 FROM &tracking_table SP
                    WHERE SP.OTT_ID = D.&detail_id_col)
       AND NOT EXISTS (SELECT 1 FROM &tracking_table SP
                        WHERE SP.OTT_ID  = D.&detail_id_col
                          AND SP.OTT_CTR = D.&detail_ctr_col);

    IF v_untracked > 0 THEN
        raise_application_error(-20009,
            v_untracked || ' part(s) of already-tracked documents are still untracked. '
            || 'The migration is INCOMPLETE - do NOT start Squish. Re-run this script.');
    END IF;

    DBMS_OUTPUT.PUT_LINE('  OK: every part of every tracked document is tracked');
END;
/

PROMPT
PROMPT ============================================
PROMPT Migration complete. Post-migration state:
PROMPT ============================================

SELECT STATUS, RECORD_COUNT, DOCUMENT_COUNT FROM SQUISH_STATS ORDER BY STATUS;

-- MIGRATED = parts this script ASSUMED were already compressed. They will never be
-- compressed unless you delete these rows (see RECOVERY in the header).
SELECT COUNT(*) AS ASSUMED_ROWS FROM &tracking_table WHERE STATUS = 'MIGRATED';

SELECT constraint_name, constraint_type FROM user_constraints
 WHERE table_name = UPPER('&tracking_table') AND constraint_type = 'U';

PROMPT
PROMPT Now deploy the new Squish jar. Re-grants made for this migration
PROMPT (e.g. CREATE TABLE) can be revoked again.
PROMPT ============================================

EXIT SUCCESS
