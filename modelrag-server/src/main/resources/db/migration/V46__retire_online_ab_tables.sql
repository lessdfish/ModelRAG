DROP TABLE IF EXISTS kb_ab_event;
DROP TABLE IF EXISTS kb_ab_experiment;

DO $$
DECLARE
    vector_version TEXT;
BEGIN
    SELECT extversion INTO vector_version FROM pg_extension WHERE extname = 'vector';
    IF vector_version IS NULL
       OR split_part(vector_version, '.', 1)::INT < 0
       OR (split_part(vector_version, '.', 1)::INT = 0
           AND split_part(vector_version, '.', 2)::INT < 8) THEN
        RAISE EXCEPTION 'pgvector 0.8+ is required for filtered ANN iterative scan; found %', vector_version;
    END IF;
END $$;
