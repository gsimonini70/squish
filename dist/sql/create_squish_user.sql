-- ============================================
-- Squish - Dedicated Database User (Oracle 11g compatible)
-- ============================================
-- Creates a least-privilege Oracle user for Squish and grants it exactly what
-- the application executes - nothing more.
--
-- What Squish actually does against the database:
--   * SELECT on the master table            (id, filename, filter)
--   * SELECT on the detail table            (reads the PDF BLOB)
--   * UPDATE of ONE column on the detail    (writes the compressed BLOB back)
--   * SELECT + INSERT + UPDATE on tracking  (MERGE, plus the NOT EXISTS resume anti-join)
--
-- It never issues DELETE, DROP or ALTER, and it executes no DDL at runtime.
-- The UPDATE grant below is COLUMN-LEVEL: Squish is structurally incapable of
-- modifying any other column of the detail table, even if it had a bug.
--
-- Run AFTER this script: create_tracking_table.sql, connected as the Squish user
-- (see section 3), so the tracking table is born in the Squish schema.
--
-- This script is IDEMPOTENT: re-running it is a no-op.
-- ============================================

-- --------------------------------------------
-- EDIT THESE
-- --------------------------------------------
DEFINE squish_user  = SQUISH
DEFINE data_owner   = MC_CONSCR        -- schema that owns OTTICA / OTTICAI
DEFINE master_table = OTTICA
DEFINE detail_table = OTTICAI
DEFINE blob_column  = OTTI_DATA    -- the ONLY column Squish may write
DEFINE tablespace   = USERS        -- for the tracking table (small: one row per PDF)
DEFINE quota        = 100M

SET SERVEROUTPUT ON
WHENEVER SQLERROR EXIT FAILURE

-- VERIFY OFF is a SECURITY requirement, not cosmetics: with the default VERIFY ON,
-- SQL*Plus echoes every substituted line as "old:/new:" - which would print the
-- password in clear on the terminal (and into any spool file), defeating the HIDE
-- below.
SET VERIFY OFF

-- The password is prompted for, never stored in this file. Consistent with the
-- rest of the kit, where credentials live only in config/squish.env (chmod 600).
-- Avoid '&' and single quotes in it: SQL*Plus substitutes before parsing.
ACCEPT squish_password CHAR PROMPT 'Password for &squish_user: ' HIDE

-- ============================================
-- SECTION 1 - connect as DBA
-- ============================================
-- Creates the user and grants the system privileges.
--
-- The DDL privileges (CREATE TABLE/SEQUENCE/TRIGGER/VIEW/SYNONYM) are needed ONLY
-- to run section 3. Section 4 revokes them: at runtime Squish needs none of them.

DECLARE
    -- Tolerates "already exists" so the script can be re-run safely.
    --   ORA-01920: user name conflicts with another user
    --   ORA-00955: name is already used by an existing object
    -- Anything else is a real problem and must not be swallowed.
    PROCEDURE ddl(p_sql IN VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
        DBMS_OUTPUT.PUT_LINE('  OK      : ' || SUBSTR(p_sql, 1, 70));
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE IN (-1920, -955) THEN
                DBMS_OUTPUT.PUT_LINE('  EXISTS  : ' || SUBSTR(p_sql, 1, 70));
            ELSE
                RAISE;
            END IF;
    END;
BEGIN
    DBMS_OUTPUT.PUT_LINE('--- Section 1: user and system privileges ---');

    -- If the user already exists this is reported as EXISTS and skipped, which means
    -- re-running the script does NOT reset the password of an existing account.
    -- That is deliberate. To change it: ALTER USER &squish_user IDENTIFIED BY ...
    ddl('CREATE USER &squish_user IDENTIFIED BY "&squish_password"');

    -- Quota, NOT "UNLIMITED TABLESPACE". Squish only ever stores the tracking
    -- table; the PDFs stay in the data owner's schema.
    ddl('ALTER USER &squish_user QUOTA &quota ON &tablespace');

    ddl('GRANT CREATE SESSION TO &squish_user');

    -- Setup-only. Revoked in section 4.
    ddl('GRANT CREATE TABLE, CREATE SEQUENCE, CREATE TRIGGER, CREATE VIEW TO &squish_user');
    ddl('GRANT CREATE SYNONYM TO &squish_user');

    -- Deliberately NOT granted: RESOURCE, DBA, UNLIMITED TABLESPACE,
    -- CREATE ANY TABLE, SELECT ANY TABLE. None of them are needed.
END;
/

-- ============================================
-- SECTION 2 - object grants
-- ============================================
-- These run in the SAME session as section 1 if your DBA account holds
-- GRANT ANY OBJECT PRIVILEGE (the common case). If it does not, the script stops
-- here with ORA-01031 (insufficient privileges) - that is expected, and harmless:
-- section 1 has already completed. Just reconnect as the data owner and re-run
-- the three GRANTs below by hand:
--
--   CONNECT &data_owner@db
--
-- Re-running a GRANT that is already in place is a no-op in Oracle, so no
-- exception handling is needed here.

GRANT SELECT ON &data_owner..&master_table TO &squish_user;
GRANT SELECT ON &data_owner..&detail_table TO &squish_user;

-- COLUMN-LEVEL update: the BLOB and nothing else.
GRANT UPDATE (&blob_column) ON &data_owner..&detail_table TO &squish_user;

-- If the database has been hardened by revoking PUBLIC grants, Squish also needs
-- this: it calls DBMS_LOB.GETLENGTH to size the BLOBs. On a stock Oracle the
-- grant is already there via PUBLIC and the line below is redundant but harmless.
-- Symptom if missing: ORA-00904 / ORA-06550 on the very first count query.
--   (run as DBA, not as the data owner)
-- GRANT EXECUTE ON DBMS_LOB TO &squish_user;

-- ============================================
-- SECTION 3 - connect as &squish_user
-- ============================================
--   CONNECT &squish_user@db
--
-- The synonyms let squish.query.* keep the plain, unqualified table names
-- (OTTICA / OTTICAI) exactly as they ship in application.yml. Without them the
-- generated SQL would not resolve, because it runs in the Squish schema.
-- The alternative - writing FIDES.OTTICA into the config - also works, but the
-- synonyms mean the shipped configuration needs no change at all.

-- CREATE SYNONYM &master_table FOR &data_owner..&master_table;
-- CREATE SYNONYM &detail_table FOR &data_owner..&detail_table;

-- Then create the tracking table in the Squish schema:
--   @create_tracking_table.sql

-- ============================================
-- SECTION 4 - connect as DBA (hardening, after section 3)
-- ============================================
-- Once the tracking table and the synonyms exist, Squish never needs to create
-- another object. Take the DDL privileges away.
--
-- REVOKE CREATE TABLE, CREATE SEQUENCE, CREATE TRIGGER, CREATE VIEW,
--        CREATE SYNONYM FROM &squish_user;
--
-- NOTE for a future UPGRADE: migrate_tracking_composite.sql does ALTER TABLE, so it
-- needs those privileges back for the duration of the migration. Grant them, run it,
-- revoke them again.
--
-- Final state: CREATE SESSION, SELECT on two tables, UPDATE on one column,
-- and ownership of its own tracking table. Nothing else.

-- ============================================
-- VERIFY
-- ============================================
-- Run as DBA to confirm the grants landed as expected:
--
--   SELECT privilege, owner, table_name FROM dba_tab_privs
--    WHERE grantee = '&squish_user' ORDER BY owner, table_name, privilege;
--
--   SELECT privilege FROM dba_sys_privs WHERE grantee = '&squish_user';
--
--   SELECT column_name, privilege FROM dba_col_privs WHERE grantee = '&squish_user';
--
-- Expected: SELECT on OTTICA and OTTICAI, UPDATE on OTTICAI.&blob_column only,
-- CREATE SESSION, and (before section 4) the setup DDL privileges.

PROMPT
PROMPT ============================================
PROMPT Sections 1 and 2 complete:
PROMPT   user &squish_user created, object grants in place.
PROMPT
PROMPT Still to do, in order:
PROMPT   3. As &squish_user: uncomment and run the synonyms in section 3,
PROMPT                       then @create_tracking_table.sql
PROMPT   4. As DBA         : the REVOKE in section 4 (hardening)
PROMPT
PROMPT Tip: validate the account with a READ-ONLY dry run.
PROMPT   squish.dry-run=true never opens a write connection, so SELECT alone
PROMPT   is enough to prove the grants before any BLOB is ever rewritten.
PROMPT ============================================
