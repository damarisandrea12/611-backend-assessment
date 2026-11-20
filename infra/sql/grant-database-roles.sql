-- Run by CD after the Flyway migrations: sqlcmd -v env=dev|test|prod -i grant-database-roles.sql
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = 'id-paynotif-$(env)-state-worker')
    CREATE USER [id-paynotif-$(env)-state-worker] FROM EXTERNAL PROVIDER;
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = 'id-paynotif-$(env)-sync-worker')
    CREATE USER [id-paynotif-$(env)-sync-worker] FROM EXTERNAL PROVIDER;
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = 'id-paynotif-$(env)-ops-api')
    CREATE USER [id-paynotif-$(env)-ops-api] FROM EXTERNAL PROVIDER;
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = 'id-paynotif-$(env)-intake')
    CREATE USER [id-paynotif-$(env)-intake] FROM EXTERNAL PROVIDER;

IF DATABASE_PRINCIPAL_ID('app_state_writer') IS NULL CREATE ROLE app_state_writer;
IF DATABASE_PRINCIPAL_ID('app_sync_writer') IS NULL CREATE ROLE app_sync_writer;
IF DATABASE_PRINCIPAL_ID('app_ops_reader') IS NULL CREATE ROLE app_ops_reader;
IF DATABASE_PRINCIPAL_ID('app_intake_dedup') IS NULL CREATE ROLE app_intake_dedup;

GRANT SELECT, INSERT, UPDATE ON dbo.payment_state TO app_state_writer;
GRANT SELECT, INSERT, UPDATE ON dbo.notification TO app_state_writer;
GRANT SELECT, INSERT ON dbo.notification_attempt TO app_state_writer;

GRANT SELECT, UPDATE ON dbo.payment_state TO app_sync_writer;         

GRANT SELECT ON dbo.payment_state TO app_ops_reader;                  
GRANT SELECT ON dbo.notification TO app_ops_reader;
GRANT SELECT ON dbo.notification_attempt TO app_ops_reader;
GRANT EXECUTE ON OBJECT::dbo.usp_request_resync TO app_ops_reader;

GRANT SELECT ON dbo.notification TO app_intake_dedup;                
GRANT INSERT ON dbo.notification_attempt TO app_intake_dedup;        

ALTER ROLE app_state_writer ADD MEMBER [id-paynotif-$(env)-state-worker];
ALTER ROLE app_sync_writer  ADD MEMBER [id-paynotif-$(env)-sync-worker];
ALTER ROLE app_ops_reader   ADD MEMBER [id-paynotif-$(env)-ops-api];
ALTER ROLE app_intake_dedup ADD MEMBER [id-paynotif-$(env)-intake];
