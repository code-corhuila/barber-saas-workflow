-- NOLOGIN roles carry the permissions. The login user workflow_app is created by
-- barber-saas-infra-postgres from a secret; no password is ever versioned here.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workflow_reader') THEN
        CREATE ROLE workflow_reader NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workflow_writer') THEN
        CREATE ROLE workflow_writer NOLOGIN;
    END IF;
END
$$;
