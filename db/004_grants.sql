GRANT USAGE ON SCHEMA workflow TO workflow_reader, workflow_writer;
GRANT SELECT ON ALL TABLES IN SCHEMA workflow TO workflow_reader;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA workflow TO workflow_writer;
ALTER DEFAULT PRIVILEGES IN SCHEMA workflow GRANT SELECT ON TABLES TO workflow_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA workflow GRANT SELECT, INSERT, UPDATE ON TABLES TO workflow_writer;

-- The service's own login user gets the writer role (Annex J J.7). The user exists only where the
-- infrastructure created it, so the grant is conditional.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workflow_app') THEN
        GRANT workflow_writer TO workflow_app;
    END IF;
END
$$;
