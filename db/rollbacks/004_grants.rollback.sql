DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workflow_app') THEN
        REVOKE workflow_writer FROM workflow_app;
    END IF;
END
$$;
ALTER DEFAULT PRIVILEGES IN SCHEMA workflow REVOKE ALL ON TABLES FROM workflow_reader, workflow_writer;
REVOKE ALL ON ALL TABLES IN SCHEMA workflow FROM workflow_reader, workflow_writer;
REVOKE USAGE ON SCHEMA workflow FROM workflow_reader, workflow_writer;
